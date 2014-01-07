package com.keepit.commanders

import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.scraper.ScrapeSchedulerPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}


import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import com.google.inject.{Inject, Singleton}
import com.keepit.normalizer.NormalizationCandidate
import scala.util.Try
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.keepit.heimdal.HeimdalContext

case class InternedUriAndBookmark(bookmark: Bookmark, uri: NormalizedURI, isNewKeep: Boolean)

@Singleton
class BookmarkInterner @Inject() (
  db: Database,
  uriRepo: NormalizedURIRepo,
  scraper: ScrapeSchedulerPlugin,
  bookmarkRepo: BookmarkRepo,
  urlRepo: URLRepo,
  socialUserInfoRepo: SocialUserInfoRepo,
  airbrake: AirbrakeNotifier,
  keptAnalytics: KeepingAnalytics,
  keepsAbuseMonitor: KeepsAbuseMonitor,
  rawBookmarkFactory: RawBookmarkFactory,
  userValueRepo: UserValueRepo,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends Logging {

  private[commanders] def deDuplicate(rawBookmarks: Seq[RawBookmarkRepresentation]): Seq[RawBookmarkRepresentation] =
    ((rawBookmarks map { b => (b.url, b) } toMap).values.toSeq).toList

  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): Seq[Bookmark] = {
    val referenceId: UUID = UUID.randomUUID
    log.info(s"[internRawBookmarks] user=$userId source=$source installId=$installationId value=$rawBookmarks $referenceId ")
    val parseStart = System.currentTimeMillis()
    val deduped = deDuplicate(rawBookmarks)

    val bookmarks = deduped.sortWith { case (a, b) => a.url < b.url }
    keepsAbuseMonitor.inspect(userId, bookmarks.size)
    log.info(s"[internBookmarks-$referenceId] Parsing took: ${System.currentTimeMillis - parseStart}ms")
    val count = new AtomicInteger(0)
    val total = bookmarks.size
    val batchSize = 50

    db.readWrite { implicit session =>
      // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
      // If it happens, the user will experience the % complete jumping around a bit until it's finished.
      userValueRepo.setValue(userId, "bookmark_import_last_start", clock.now.toString)
      userValueRepo.setValue(userId, "bookmark_import_done", "0")
      userValueRepo.setValue(userId, "bookmark_import_total", "0")
    }

    val persistedBookmarksWithUris: List[InternedUriAndBookmark] = bookmarks.grouped(batchSize).map { bms =>
      val startTime = System.currentTimeMillis
      log.info(s"[internBookmarks-$referenceId] Persisting $batchSize bookmarks: ${count.get}/$total")
      val persisted = internUriAndBookmarkBatch(bms, userId, source, mutatePrivacy, total, count)
      val newCount = count.addAndGet(bms.size)
      log.info(s"[internBookmarks-$referenceId] Done with $newCount/$total. Took ${System.currentTimeMillis - startTime}ms")
      db.readWrite { implicit session =>
        userValueRepo.setValue(userId, "bookmark_import_done", (newCount / total).toString)
      }
      persisted
    }.flatten.toList

    db.readWrite { implicit session =>
      userValueRepo.clearValue(userId, "bookmark_import_done")
    }

    log.info(s"[internBookmarks-$referenceId] Requesting scrapes")
    val newKeeps = persistedBookmarksWithUris collect {
      case InternedUriAndBookmark(bm, uri, isNewBookmark) if isNewBookmark => bm
    }
    keptAnalytics.keptPages(userId, newKeeps, context)

    val persistedBookmarks = persistedBookmarksWithUris.map(_.bookmark)
    log.info(s"[internBookmarks-$referenceId] Done!")
    persistedBookmarks
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, total: Int, count: AtomicInteger) = {
    val persisted = try {
      db.readWrite(attempts = 3) { implicit session =>
        bms.map { bm => internUriAndBookmark(bm, userId, source, mutatePrivacy) }.flatten
      }
    } catch {
      case e: Exception =>
        airbrake.notify(s"failed to persist a batch of ${bms.size} of $total so far ${count.get} raw bookmarks of user $userId from $source: ${bms map {b => b.url} mkString ","}", e)
        Seq()
    }
    persisted
  }

  private def internBookmark(uri: NormalizedURI, userId: Id[User], isPrivate: Boolean, mutatePrivacy: Boolean,
      installationId: Option[ExternalId[KifiInstallation]], source: BookmarkSource, title: Option[String], url: String)(implicit session: RWSession) = {
    bookmarkRepo.getByUriAndUser(uri.id.get, userId, excludeState = None) match {
      case Some(bookmark) =>
        val keepWithPrivate = if (mutatePrivacy) bookmark.copy(isPrivate = isPrivate) else bookmark
        val keep = if (!bookmark.isActive) { keepWithPrivate.withUrl(url).withActive(true).copy(createdAt = clock.now) } else keepWithPrivate
        val keepWithTitle = keep.withTitle(title orElse bookmark.title orElse uri.title)
        val persistedKeep = if(keepWithTitle != bookmark) bookmarkRepo.save(keepWithTitle) else bookmark
        (false, persistedKeep)
      case None =>
        val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        (true, bookmarkRepo.save(BookmarkFactory(uri, userId, title orElse uri.title, urlObj, source, isPrivate, installationId)))
    }
  }

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit session: RWSession): Option[InternedUriAndBookmark] = try {
    if (!rawBookmark.url.toLowerCase.startsWith("javascript:")) {
      val uri = {
        val initialURI = uriRepo.internByUri(rawBookmark.url, NormalizationCandidate(rawBookmark):_*)
        if (initialURI.state == NormalizedURIStates.ACTIVE | initialURI.state == NormalizedURIStates.INACTIVE)
          uriRepo.save(initialURI.withState(NormalizedURIStates.SCRAPE_WANTED))
        else initialURI
      }
      val (isNewKeep, bookmark) = internBookmark(uri, userId, rawBookmark.isPrivate, mutatePrivacy, installationId, source, rawBookmark.title, rawBookmark.url)

      if (uri.state == NormalizedURIStates.SCRAPE_WANTED) {
        Try(scraper.scheduleScrape(uri))
      }

      session.conn.commit()

      Some(InternedUriAndBookmark(bookmark, uri, isNewKeep))
    } else {
      None
    }
  } catch {
    case e: Exception =>
      //note that at this point we continue on. we don't want to mess the upload of entire user bookmarks because of one bad bookmark.
      airbrake.notify(AirbrakeError(
        exception = e,
        message = Some(s"Exception while loading one of the bookmarks of user $userId: ${e.getMessage} from: $rawBookmark source: $source")))
      None
  }
}

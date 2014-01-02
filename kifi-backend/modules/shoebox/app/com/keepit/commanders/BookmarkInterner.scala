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

  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], user: User, experiments: Set[ExperimentType], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): Seq[Bookmark] = {

    val referenceId: UUID = UUID.randomUUID
    log.info(s"[internRawBookmarks] user=(${user.id} ${user.firstName} ${user.lastName}) source=$source installId=$installationId value=$rawBookmarks $referenceId ")
    val parseStart = System.currentTimeMillis()
    val bookmarks = rawBookmarks.sortWith { case (a, b) => a.url < b.url }
    log.info(s"[internBookmarks-$referenceId] Parsing took: ${System.currentTimeMillis - parseStart}ms")
    keepsAbuseMonitor.inspect(user.id.get, bookmarks.size)
    val count = new AtomicInteger(0)
    val total = bookmarks.size
    val batchSize = 100

    db.readWrite { implicit session =>
      // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
      // If it happens, the user will experience the % complete jumping around a bit until it's finished.
      userValueRepo.setValue(user.id.get, "bookmark_import_last_start", clock.now.toString)
      userValueRepo.setValue(user.id.get, "bookmark_import_done", "0")
      userValueRepo.setValue(user.id.get, "bookmark_import_total", "0")
    }

    val persistedBookmarksWithUris = bookmarks.grouped(batchSize).map { bms =>
      val startTime = System.currentTimeMillis
      log.info(s"[internBookmarks-$referenceId] Persisting $batchSize bookmarks: ${count.get}/$total")
      val persisted = db.readWrite(attempts = 2) { implicit session =>
        bms.map { bm => internUriAndBookmark(bm, user, experiments, source, mutatePrivacy) }.flatten
      }
      val newCount = count.addAndGet(bms.size)
      log.info(s"[internBookmarks-$referenceId] Done with $newCount/$total. Took ${System.currentTimeMillis - startTime}ms")
      db.readWrite { implicit session =>
        userValueRepo.setValue(user.id.get, "bookmark_import_done", (newCount / total).toString)
      }
      persisted
    }.flatten.toList

    db.readWrite { implicit session =>
      userValueRepo.clearValue(user.id.get, "bookmark_import_done")
    }

    log.info(s"[internBookmarks-$referenceId] Requesting scrapes")
    val newKeeps = persistedBookmarksWithUris collect { case (bm, uri, isNewBookmark) if isNewBookmark => bm }
    keptAnalytics.keptPages(user.id.get, newKeeps, context)

    val persistedBookmarks = persistedBookmarksWithUris.map(_._1)
    log.info(s"[internBookmarks-$referenceId] Done!")
    persistedBookmarks
  }

  private def internBookmark(uri: NormalizedURI, user: User, isPrivate: Boolean, mutatePrivacy: Boolean, experiments: Set[ExperimentType],
      installationId: Option[ExternalId[KifiInstallation]], source: BookmarkSource, title: Option[String], url: String)(implicit session: RWSession) = {
    bookmarkRepo.getByUriAndUser(uri.id.get, user.id.get, excludeState = None) match {
      case Some(bookmark) =>
        val keepWithPrivate = if (mutatePrivacy) bookmark.copy(isPrivate = isPrivate) else bookmark
        val keep = if (!bookmark.isActive) { keepWithPrivate.withUrl(url).withActive(true).copy(createdAt = clock.now) } else keepWithPrivate
        val keepWithTitle = keep.withTitle(title orElse bookmark.title orElse uri.title)
        val persistedKeep = if(keepWithTitle != bookmark) bookmarkRepo.save(keepWithTitle) else bookmark
        (false, persistedKeep)
      case None =>
        val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        (true, bookmarkRepo.save(BookmarkFactory(uri, user.id.get, title orElse uri.title, urlObj, source, isPrivate, installationId)))
    }
  }

  private def internUriAndBookmark(rawBookmark: RawBookmarkRepresentation, user: User, experiments: Set[ExperimentType], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit session: RWSession): Option[(Bookmark, NormalizedURI, Boolean)] = try {
    if (!rawBookmark.url.toLowerCase.startsWith("javascript:")) {
      val uri = {
        val initialURI = uriRepo.internByUri(rawBookmark.url, NormalizationCandidate(rawBookmark):_*)
        if (initialURI.state == NormalizedURIStates.ACTIVE | initialURI.state == NormalizedURIStates.INACTIVE)
          uriRepo.save(initialURI.withState(NormalizedURIStates.SCRAPE_WANTED))
        else initialURI
      }
      val (isNewKeep, bookmark) = internBookmark(uri, user, rawBookmark.isPrivate, mutatePrivacy, experiments, installationId, source, rawBookmark.title, rawBookmark.url)

      if (uri.state == NormalizedURIStates.SCRAPE_WANTED) {
        Try(scraper.scheduleScrape(uri))
      }

      session.conn.commit()

      Some((bookmark, uri, isNewKeep))
    } else {
      None
    }
  } catch {
    case e: Exception =>
      //note that at this point we continue on. we don't want to mess the upload of entire user bookmarks because of one bad bookmark.
      airbrake.notify(AirbrakeError(
        exception = e,
        message = Some(s"Exception while loading one of the bookmarks of user $user: ${e.getMessage} from: $rawBookmark source: $source")))
      None
  }
}

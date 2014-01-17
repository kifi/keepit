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
import com.keepit.common.akka.{UnsupportedActorMessage, FortyTwoActor}
import play.api.Plugin
import akka.actor.ActorSystem
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.shoebox.ShoeboxServiceClient


private case object ProcessKeeps
private class RawKeepImporterActor @Inject() (
  db: Database,
  rawKeepRepo: RawKeepRepo,
  airbrake: AirbrakeNotifier,
  clock: Clock
) extends FortyTwoActor(airbrake) with Logging {

  private val batchSize = 200
  def receive = {
    case ProcessKeeps =>
      val activeBatch = fetchActiveBatch()
      processBatch(activeBatch)
      val oldBatch = fetchOldBatch()
      processBatch(oldBatch)

      val totalProcessed = activeBatch.length + oldBatch.length
      if (totalProcessed > 0) { // batch was non-empty, so there may be more to process
        self ! ProcessKeeps
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  def fetchActiveBatch() = {
    db.readWrite { implicit session =>
      rawKeepRepo.getUnprocessedAndMarkAsImporting(batchSize)
    }
  }

  def fetchOldBatch() = {
    db.readWrite { implicit session =>
      rawKeepRepo.getOldUnprocessed(batchSize, clock.now.minusMinutes(3))
    }
  }

  def processBatch(rawKeeps: Seq[RawKeep]): (Seq[Bookmark], Seq[RawKeep]) = {
    // Use Yasu's thing!

    val failedResults: Option[_] = ???
    val userToImportedCount = new TrieMap[Id[User], Int]()

    if (failedResults.nonEmpty) {
      db.readWrite { implicit session =>
        failedResults.map { failed =>
          rawKeepRepo.setState(failed.id.get, RawKeepStates.FAILED)
        }
      }
    }
    ???
  }

}

trait RawKeepImporterPlugin extends Plugin {
  def processKeeps(broadcastToOthers: Boolean = false): Unit
}

class RawKeepImporterPluginImpl @Inject()(
  system: ActorSystem,
  actor: ActorInstance[RawKeepImporterActor],
  serviceDiscovery: ServiceDiscovery,
  shoeboxServiceClient: ShoeboxServiceClient,
  val scheduling: SchedulingProperties //only on leader
) extends SchedulerPlugin with RawKeepImporterPlugin {

  def processKeeps(broadcastToOthers: Boolean = false): Unit = {
    if (serviceDiscovery.isLeader()) {
      actor.ref ! ProcessKeeps
    } else if(broadcastToOthers) {
      shoeboxServiceClient.triggerRawKeepImport()
    }
  }

  override def onStart() {
    scheduleTaskOnLeader(system, 70 seconds, 1 minute, actor.ref, ProcessKeeps)
    super.onStart()
  }
}



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
  rawKeepRepo: RawKeepRepo,
  rawKeepImporterPlugin: RawKeepImporterPlugin,
  implicit private val clock: Clock,
  implicit private val fortyTwoServices: FortyTwoServices)
    extends Logging {

  private[commanders] def deDuplicateRawKeep(rawKeeps: Seq[RawKeep]): Seq[RawKeep] =
    rawKeeps.map(b => (b.url, b)).toMap.values.toList

  // Persists keeps to RawKeep, which will be batch processed. Very minimal pre-processing.
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None) = {
    log.info(s"[persistRawKeeps] persisting batch of ${rawKeeps.size} keeps")
    val newImportId = importId.getOrElse(UUID.randomUUID.toString)

    val deduped = deDuplicateRawKeep(rawKeeps) map(_.copy(importId = Some(newImportId)))

    if (deduped.nonEmpty) {
      val userId = deduped.head.userId
      val total = deduped.size

      keepsAbuseMonitor.inspect(userId, total)

      db.readWrite { implicit session =>
        // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
        // If it happens, the user will experience the % complete jumping around a bit until it's finished.
        userValueRepo.setValue(userId, "bookmark_import_last_start", clock.now.toString)
        userValueRepo.setValue(userId, "bookmark_import_done", "0")
        userValueRepo.setValue(userId, "bookmark_import_total", total.toString)
      }

      deduped.grouped(500).map { rawKeepGroup =>
      // insertAll fails (with an exception) if any of the inserts failed
        val bulkAttempt = db.readWrite(attempts = 2) { implicit session =>
          rawKeepRepo.insertAll(rawKeepGroup)
        }
        if (bulkAttempt.isFailure) {
          val singleAttempt = db.readWrite { implicit session =>
            rawKeepGroup.map { rawKeep =>
              rawKeep -> rawKeepRepo.insert(rawKeep)
            }
          }
          val (_, failuresWithRaws) = singleAttempt.partition(_._2.isSuccess)
          val failedUrls = failuresWithRaws.map(_._1.url)
          if (failedUrls.nonEmpty) {
            airbrake.notify(s"failed to persist ${failedUrls.size} raw keeps: ${failedUrls mkString ","}")
          }
        }
        rawKeepImporterPlugin.processKeeps()
      }
    }
  }

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
    val batchSize = 20

    db.readWrite { implicit session =>
      // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
      // If it happens, the user will experience the % complete jumping around a bit until it's finished.
      userValueRepo.setValue(userId, "bookmark_import_last_start", clock.now.toString)
      userValueRepo.setValue(userId, "bookmark_import_done", "0")
      userValueRepo.setValue(userId, "bookmark_import_total", total.toString)
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
        if (initialURI.state == NormalizedURIStates.ACTIVE || initialURI.state == NormalizedURIStates.INACTIVE)
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

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
import play.api.libs.json.Json


private case object ProcessKeeps
private class RawKeepImporterActor @Inject() (
  db: Database,
  rawKeepRepo: RawKeepRepo,
  bookmarkInterner: BookmarkInterner,
  bookmarkRepo: BookmarkRepo,
  uriRepo: NormalizedURIRepo,
  userValueRepo: UserValueRepo,
  airbrake: AirbrakeNotifier,
  urlRepo: URLRepo,
  scraper: ScrapeSchedulerPlugin,
  keptAnalytics: KeepingAnalytics,
  clock: Clock
) extends FortyTwoActor(airbrake) with Logging {

  private val batchSize = 500
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
    db.readOnly { implicit session =>
      rawKeepRepo.getOldUnprocessed(batchSize, clock.now.minusMinutes(3))
    }
  }

  def processBatch(rawKeeps: Seq[RawKeep]): Unit = {
    rawKeeps.groupBy(rk => (rk.userId, rk.importId)).map { case ((userId, importIdOpt), rawKeepGroup) =>
      val context = importIdOpt.map(importId => getHeimdalContext(userId, importId)).flatten.getOrElse(HeimdalContext.empty)
      val (successes, failures) = bookmarkInterner.internRawKeeps(rawKeepGroup, userId, mutatePrivacy = true)(context)
      if (failures.nonEmpty) {
        db.readWrite { implicit session =>
          failures.map { failed =>
            rawKeepRepo.setState(failed.id.get, RawKeepStates.FAILED)
          }
        }
      }
    }
  }

  def getHeimdalContext(userId: Id[User], importId: String): Option[HeimdalContext] = {
    Try {
      db.readOnly { implicit session =>
        userValueRepo.getValue(userId, s"bookmark_import_${importId}_context")
      }.map { jsonStr =>
        Json.fromJson[HeimdalContext](Json.parse(jsonStr)).asOpt
      }.flatten
    }.toOption.flatten
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
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext) = {
    log.info(s"[persistRawKeeps] persisting batch of ${rawKeeps.size} keeps")
    val newImportId = importId.getOrElse(UUID.randomUUID.toString)

    val deduped = deDuplicateRawKeep(rawKeeps) map(_.copy(importId = Some(newImportId), isPrivate = true))

    if (deduped.nonEmpty) {
      val userId = deduped.head.userId
      val total = deduped.size

      keepsAbuseMonitor.inspect(userId, total)
      keptAnalytics.keepImport(userId, clock.now, context, total)

      db.readWrite { implicit session =>
        // This isn't designed to handle multiple imports at once. When we need this, it'll need to be tweaked.
        // If it happens, the user will experience the % complete jumping around a bit until it's finished.
        userValueRepo.setValue(userId, "bookmark_import_last_start", clock.now.toString)
        userValueRepo.setValue(userId, "bookmark_import_done", "0")
        userValueRepo.setValue(userId, "bookmark_import_total", total.toString)
        userValueRepo.setValue(userId, s"bookmark_import_${newImportId}_context", Json.toJson(context).toString())
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

  def internRawKeeps(rawKeeps: Seq[RawKeep], userId: Id[User], mutatePrivacy: Boolean)(implicit context: HeimdalContext): (Seq[Bookmark], Seq[RawKeep]) = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawKeeps, mutatePrivacy)
    val newKeeps = persistedBookmarksWithUris collect {
      case InternedUriAndBookmark(bm, uri, isNewBookmark) if isNewBookmark => bm
    }
    newKeeps.foreach { bookmarks =>
      keptAnalytics.keptPages(userId, newKeeps, context)
    }

    (persistedBookmarksWithUris.map(_.bookmark), failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawKeep], mutatePrivacy: Boolean) = {
    val (persisted, failed) = db.readWriteBatch(bms, attempts = 3) { (session, bm) =>
      internUriAndBookmark(bm, bm.userId, bm.source, mutatePrivacy)(session)
    }.partition{ case (bm, res) => res.isSuccess }

    if (failed.nonEmpty) {
      airbrake.notify(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks: look app.log for urls")
      bms.foreach{ b => log.error(s"failed to persist raw bookmarks of user ${b.userId} from ${b.source}: ${b.url}") }
    }

    (persisted.values.map(_.get).flatten.toSeq, failed.keys.toList)
  }

  private def internUriAndBookmark(rawBookmark: RawKeep, userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit session: RWSession): Option[InternedUriAndBookmark] = try {
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

  private def internBookmark(uri: NormalizedURI, userId: Id[User], isPrivate: Boolean, mutatePrivacy: Boolean,
      installationId: Option[ExternalId[KifiInstallation]], source: BookmarkSource, title: Option[String], url: String)(implicit session: RWSession) = {
    bookmarkRepo.getByUriAndUserAllStates(uri.id.get, userId) match {
      case Some(bookmark) =>
        val keepWithPrivate = if (mutatePrivacy) bookmark.copy(isPrivate = isPrivate) else bookmark
        val keep = if (!bookmark.isActive) { keepWithPrivate.withUrl(url).withActive(isActive = true).copy(createdAt = clock.now) } else keepWithPrivate
        val keepWithTitle = keep.withTitle(title orElse bookmark.title orElse uri.title)
        val persistedKeep = if(keepWithTitle != bookmark) bookmarkRepo.save(keepWithTitle) else bookmark
        (false, persistedKeep)
      case None =>
        val urlObj = urlRepo.get(url).getOrElse(urlRepo.save(URLFactory(url = url, normalizedUriId = uri.id.get)))
        (true, bookmarkRepo.save(BookmarkFactory(uri, userId, title orElse uri.title, urlObj, source, isPrivate, installationId)))
    }
  }

}

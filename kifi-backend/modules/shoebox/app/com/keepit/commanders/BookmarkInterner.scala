package com.keepit.commanders

import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.scraper.ScrapeSchedulerPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{AirbrakeNotifier, AirbrakeError}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.keepit.common.time._
import com.keepit.common.service.FortyTwoServices

import com.google.inject.{Inject, Singleton}
import com.keepit.normalizer.NormalizationCandidate
import scala.util.Try
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import com.keepit.heimdal.HeimdalContext
import com.keepit.common.akka.{SafeFuture, UnsupportedActorMessage, FortyTwoActor}
import play.api.Plugin
import akka.actor.ActorSystem
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import scala.concurrent.duration._
import scala.collection.concurrent.TrieMap
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json
import com.keepit.search.SearchServiceClient


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
  kifiInstallationRepo: KifiInstallationRepo,
  bookmarksCommander: BookmarksCommander,
  searchClient: SearchServiceClient,
  clock: Clock
) extends FortyTwoActor(airbrake) with Logging {

  private val batchSize = 500
  def receive = {
    case ProcessKeeps =>
      log.info(s"[RawKeepImporterActor] Running raw keep process")
      val activeBatch = fetchActiveBatch()
      processBatch(activeBatch, "active")
      val oldBatch = fetchOldBatch()
      processBatch(oldBatch, "old")

      val totalProcessed = activeBatch.length + oldBatch.length
      if (totalProcessed >= batchSize) { // batch was non-empty, so there may be more to process
        log.info(s"[RawKeepImporterActor] Looks like there may be more. Self-calling myself.")
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

  def processBatch(rawKeeps: Seq[RawKeep], reason: String): Unit = {
    log.info(s"[RawKeepImporterActor] Processing ($reason) ${rawKeeps.length} keeps")

    rawKeeps.groupBy(rk => (rk.userId, rk.importId, rk.source, rk.installationId)).map { case ((userId, importIdOpt, source, installationId), rawKeepGroup) =>
      val context = importIdOpt.map(importId => getHeimdalContext(userId, importId)).flatten.getOrElse(HeimdalContext.empty)
      val rawBookmarks = rawKeepGroup.map { rk =>
        val canonical = rk.originalJson.map(json => (json \ Normalization.CANONICAL.scheme).asOpt[String]).flatten
        val openGraph = rk.originalJson.map(json => (json \ Normalization.OPENGRAPH.scheme).asOpt[String]).flatten
        RawBookmarkRepresentation(title = rk.title, url = rk.url, isPrivate = rk.isPrivate, canonical = canonical, openGraph = openGraph)
      }
      val (successes, failures) = bookmarkInterner.internRawBookmarks(rawBookmarks, userId, source, mutatePrivacy = true)(context)
      val rawKeepByUrl = rawKeepGroup.map(rk => rk.url -> rk).toMap

      val failuresRawKeep = failures.map(s => rawKeepByUrl.get(s.url)).flatten.toSet
      val successesRawKeep = rawKeepGroup.filterNot(v => failuresRawKeep.contains(v))

      if (failuresRawKeep.nonEmpty) {
        db.readWriteBatch(failuresRawKeep.toSeq) { case (session, rk) =>
          rawKeepRepo.setState(rk.id.get, RawKeepStates.FAILED)(session)
        }
      }

      if (successes.nonEmpty) {
        if (source == BookmarkSource.bookmarkImport && installationId.isDefined) {
          // User selected to import Léo
          val tagName = db.readOnly { implicit session =>
            "Imported" + kifiInstallationRepo.getOpt(installationId.get).map(v => s" from ${v.userAgent.name}").getOrElse("")
          }
          val tag = bookmarksCommander.getOrCreateTag(userId, tagName)(context)
          bookmarksCommander.addToCollection(tag, successes)(context)
        }
        //the bookmarks list may be very large!
        searchClient.updateBrowsingHistory(userId, successes.map(_.uriId): _*)
        searchClient.updateURIGraph()

        db.readWriteBatch(successesRawKeep) { case (session, rk) =>
          rawKeepRepo.setState(rk.id.get, RawKeepStates.IMPORTED)(session)
        }
      }
      log.info(s"[RawKeepImporterActor] Interned ${successes.length + failures.length} keeps. ${successes.length} successes, ${failures.length} failures.")

      val (doneOpt, totalOpt) = db.readOnly { implicit session =>
        (userValueRepo.getValue(userId, "bookmark_import_done").map(_.toInt),
          userValueRepo.getValue(userId, "bookmark_import_total").map(_.toInt))
      }

      db.readWrite { implicit session =>
        (doneOpt, totalOpt) match {
          case (Some(done), Some(total)) =>
            if (done + rawKeepGroup.length >= total) { // Import is done
              userValueRepo.clearValue(userId, "bookmark_import_done")
              userValueRepo.clearValue(userId, "bookmark_import_total")
              importIdOpt.map { importId =>
                userValueRepo.clearValue(userId, s"bookmark_import_${importId}_context")
              }
            } else {
              userValueRepo.setValue(userId, "bookmark_import_done", (done + rawKeepGroup.length).toString)
            }
          case _ =>
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
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. I'm leader.")
      actor.ref ! ProcessKeeps
    } else if(broadcastToOthers) {
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. Sending to leader.")
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
  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit = {
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

      deduped.grouped(500).toList.map { rawKeepGroup =>
      // insertAll fails if any of the inserts failed
        log.info(s"[persistRawKeeps] Persisting ${rawKeepGroup.length} raw keeps")
        val bulkAttempt = db.readWrite(attempts = 2) { implicit session =>
          rawKeepRepo.insertAll(rawKeepGroup)
        }
        log.info(s"[persistRawKeeps] Persist result: ${bulkAttempt}")
        if (bulkAttempt.isFailure) {
          log.info(s"[persistRawKeeps] Trying one at a time")
          val singleAttempt = db.readWrite { implicit session =>
            rawKeepGroup.map { rawKeep =>
              rawKeep -> rawKeepRepo.insertOne(rawKeep)
            }
          }
          val (_, failuresWithRaws) = singleAttempt.partition(_._2.isSuccess)
          val failedUrls = failuresWithRaws.map(_._1.url)
          if (failedUrls.nonEmpty) {
            airbrake.notify(s"failed to persist ${failedUrls.size} raw keeps: ${failedUrls mkString ","}")
          }
        }
      }
      rawKeepImporterPlugin.processKeeps()
    }
  }


  def internRawBookmarks(rawBookmarks: Seq[RawBookmarkRepresentation], userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None)(implicit context: HeimdalContext): (Seq[Bookmark], Seq[RawBookmarkRepresentation]) = {
    val (persistedBookmarksWithUris, failures) = internUriAndBookmarkBatch(rawBookmarks, userId, source, mutatePrivacy)
    val newKeeps = persistedBookmarksWithUris collect {
      case InternedUriAndBookmark(bm, uri, isNewBookmark) if isNewBookmark => bm
    }

    keptAnalytics.keptPages(userId, newKeeps, context)

    (persistedBookmarksWithUris.map(_.bookmark), failures)
  }

  private def internUriAndBookmarkBatch(bms: Seq[RawBookmarkRepresentation], userId: Id[User], source: BookmarkSource, mutatePrivacy: Boolean, installationId: Option[ExternalId[KifiInstallation]] = None) = {
    val (persisted, failed) = db.readWriteBatch(bms, attempts = 3) { (session, bm) =>
      internUriAndBookmark(bm, userId, source, mutatePrivacy, installationId)(session)
    }.partition{ case (bm, res) => res.isSuccess }

    if (failed.nonEmpty) {
      airbrake.notify(s"failed to persist ${failed.size} of ${bms.size} raw bookmarks: look app.log for urls")
      bms.foreach{ b => log.error(s"failed to persist raw bookmarks of user ${userId} from ${source}: ${b.url}") }
    }

    (persisted.values.map(_.get).flatten.toSeq, failed.keys.toList)
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

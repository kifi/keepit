package com.keepit.commanders

import java.util.UUID
import java.util.concurrent.{ Callable, TimeUnit }

import com.google.common.cache.{ Cache, CacheBuilder }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.controllers.website.Bookmark
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.{ AirbrakeError, AirbrakeNotifier }
import com.keepit.common.core._

import com.keepit.common.time._

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.rover.RoverServiceClient
import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success, Try }
import com.keepit.heimdal.HeimdalContext
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import play.api.{ db, Plugin }
import akka.actor.ActorSystem
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.{ JsArray, Json }
import scala.collection.mutable

private case object ProcessKeeps

private class RawKeepImporterActor @Inject() (
    db: Database,
    rawKeepRepo: RawKeepRepo,
    bookmarkInternerProvider: Provider[KeepInterner],
    uriRepo: NormalizedURIRepo,
    userValueRepo: UserValueRepo,
    libraryRepo: LibraryRepo,
    airbrake: AirbrakeNotifier,
    rover: RoverServiceClient,
    clock: Clock,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  private val batchSize = 500
  def receive = {
    case ProcessKeeps =>
      throw new Exception()
      log.info(s"[RawKeepImporterActor] Running raw keep process")
      val activeBatch = fetchActiveBatch()
      processBatch(activeBatch, "active")
      val oldBatchSize = if (activeBatch.length <= 10) {
        val oldBatch = fetchOldBatch()
        processBatch(oldBatch, "old")
        oldBatch.length
      } else 0

      val totalProcessed = activeBatch.length + oldBatchSize
      if (totalProcessed >= batchSize / 2) { // batch was pretty full, so there may be more to process
        log.info(s"[RawKeepImporterActor] Looks like there may be more. Self-calling myself.")
        //self ! ProcessKeeps
      }
    case m => throw new UnsupportedActorMessage(m)
  }

  private def fetchActiveBatch() = {
    db.readWrite { implicit session =>
      rawKeepRepo.getUnprocessedAndMarkAsImporting(batchSize)
    }
  }

  private def fetchOldBatch() = {
    db.readOnlyReplica { implicit session =>
      rawKeepRepo.getOldUnprocessed(batchSize, clock.now.minusMinutes(10))
    }
  }

  private def processBatch(rawKeeps: Seq[RawKeep], reason: String): Unit = {
    log.info(s"[RawKeepImporterActor] Processing ($reason) ${rawKeeps.length} keeps")

    rawKeeps.groupBy(rk => (rk.userId, rk.importId, rk.source, rk.installationId, rk.libraryId)).foreach {
      case ((userId, importIdOpt, source, installationId, libraryId), rawKeepGroup) =>

        val ctx = importIdOpt.flatMap(importId => getHeimdalContext(userId, importId)).getOrElse(HeimdalContext.empty)

        internKeeps(userId, libraryId.get, rawKeepGroup, source, ctx)
    }
  }

  private val forbiddenImportedTags = Set("Bookmarks Bar", "no_tag", "New folder", "Mobile bookmarks")
  private def parseRawBookmarksFromJson(rawKeepGroup: Seq[RawKeep]) = {
    rawKeepGroup.map { rk =>
      val canonical = rk.originalJson.flatMap(json => (json \ Normalization.CANONICAL.scheme).asOpt[String])
      val openGraph = rk.originalJson.flatMap(json => (json \ Normalization.OPENGRAPH.scheme).asOpt[String])
      val attribution = RawKeep.extractKeepSourceAttribution(rk)
      val note = rk.originalJson.flatMap(json => (json \ "note").asOpt[String]).map(Hashtags.formatExternalNote)
      val tags = rk.keepTags.flatMap(_.asOpt[Seq[String]]).getOrElse(Seq.empty).filterNot(forbiddenImportedTags.contains)
      val noteWithTags = Option(Hashtags.addTagsToString(note.getOrElse(""), tags)).filter(_.trim.nonEmpty)
      RawBookmarkRepresentation(title = rk.title, url = rk.url, canonical = canonical, openGraph = openGraph, keptAt = rk.createdDate, sourceAttribution = attribution, note = noteWithTags)
    }.distinctBy(_.url)
  }

  private def internKeeps(userId: Id[User], libraryId: Id[Library], rawKeepGroup: Seq[RawKeep], source: KeepSource, ctx: HeimdalContext) = {
    val rawBookmarks = parseRawBookmarksFromJson(rawKeepGroup)
    val library = db.readWrite { implicit s => libraryRepo.get(libraryId) }
    val (successes, failures) = bookmarkInternerProvider.get.internRawBookmarks(rawBookmarks, userId, library, source)(ctx)
    val rawKeepByUrl = rawKeepGroup.map(rk => rk.url -> rk).toMap

    val failuresRawKeep = failures.flatMap(s => rawKeepByUrl.get(s.url)).toSet
    val successesRawKeep = rawKeepGroup.filterNot(v => failuresRawKeep.contains(v))
    log.info(s"[RawKeepImporterActor] Interned ${successes.length + failures.length} keeps. ${successes.length} successes, ${failures.length} failures.")

    if (failuresRawKeep.nonEmpty) {
      db.readWriteBatch(failuresRawKeep.toSeq, attempts = 5) {
        case (session, rk) =>
          rawKeepRepo.setState(rk.id.get, RawKeepStates.FAILED)(session)
      }
    }

    if (successesRawKeep.nonEmpty) {
      db.readWriteBatch(successesRawKeep, attempts = 5) {
        case (session, rk) =>
          rawKeepRepo.setState(rk.id.get, RawKeepStates.IMPORTED)(session)
      }
    }

    successes
  }

  private def getHeimdalContext(userId: Id[User], importId: String): Option[HeimdalContext] = {
    Try {
      db.readOnlyMaster { implicit session =>
        userValueRepo.getValueStringOpt(userId, UserValueName.bookmarkImportContextName(importId))
      }.flatMap { jsonStr =>
        Json.fromJson[HeimdalContext](Json.parse(jsonStr)).asOpt
      }
    }.toOption.flatten
  }

}

trait RawKeepImporterPlugin extends Plugin {
  def processKeeps(broadcastToOthers: Boolean = false): Unit
}

@Singleton
class RawKeepImporterPluginImpl @Inject() (
    system: ActorSystem,
    actor: ActorInstance[RawKeepImporterActor],
    serviceDiscovery: ServiceDiscovery,
    shoeboxServiceClient: ShoeboxServiceClient,
    val scheduling: SchedulingProperties //only on leader
    ) extends SchedulerPlugin with RawKeepImporterPlugin {

  def processKeeps(broadcastToOthers: Boolean = false): Unit = {
    // if (serviceDiscovery.isLeader()) {
    //   log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. I'm leader.")
    //   actor.ref ! ProcessKeeps
    // } else if (broadcastToOthers) {
    //   log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. Sending to leader.")
    //   shoeboxServiceClient.triggerRawKeepImport()
    // }
  }

  override def onStart() { //keep me alive!
    // scheduleTaskOnOneMachine(system, 127 seconds, 11 seconds, actor.ref, ProcessKeeps, ProcessKeeps.getClass.getSimpleName)
    // super.onStart()
  }
}

class RawKeepInterner @Inject() (
    libraryAnalytics: LibraryAnalytics,
    keepsAbuseMonitor: KeepsAbuseMonitor,
    db: Database,
    rawKeepRepo: RawKeepRepo,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    rawKeepImporterPlugin: RawKeepImporterPlugin) extends Logging {

  // Does not persist!
  def generateRawKeeps(userId: Id[User], source: Option[KeepSource], bookmarks: Seq[Bookmark], libraryId: Id[Library]) = {
    val importId = UUID.randomUUID.toString
    val rawKeeps = bookmarks.map {
      case Bookmark(title, href, hashtags, createdDate, originalJson) =>
        val titleOpt = if (title.nonEmpty && title.exists(_.nonEmpty) && title.exists(_ != href)) Some(title.get) else None
        val hashtagsArray = if (hashtags.nonEmpty) {
          Some(JsArray(hashtags.map(Json.toJson(_))))
        } else {
          None
        }

        RawKeep(userId = userId,
          title = titleOpt,
          url = href,
          importId = Some(importId),
          source = source.getOrElse(KeepSource.BookmarkFileImport),
          originalJson = originalJson,
          installationId = None,
          keepTags = hashtagsArray,
          libraryId = Some(libraryId),
          createdDate = createdDate)
    }
    (importId, rawKeeps)
  }

  def persistRawKeeps(rawKeeps: Seq[RawKeep], importId: Option[String] = None)(implicit context: HeimdalContext): Unit = {
    log.info(s"[persistRawKeeps] persisting batch of ${rawKeeps.size} keeps")
    val newImportId = importId.getOrElse(UUID.randomUUID.toString)

    val deduped = rawKeeps.distinctBy(_.url) map (_.copy(importId = Some(newImportId)))

    // if (deduped.nonEmpty) {
    //   val userId = deduped.head.userId
    //   val total = deduped.size

    //   keepsAbuseMonitor.inspect(userId, total)
    //   libraryAnalytics.keepImport(userId, clock.now, context, total, deduped.headOption.map(_.source).getOrElse(KeepSource.BookmarkImport))

    //   deduped.grouped(500).foreach { rawKeepGroup =>
    //     // insertAll fails if any of the inserts failed
    //     log.info(s"[persistRawKeeps] Persisting ${rawKeepGroup.length} raw keeps")
    //     val bulkAttempt = db.readWrite(attempts = 4) { implicit session =>
    //       //rawKeepRepo.insertAll(rawKeepGroup)
    //     }
    //     if (bulkAttempt.isFailure) { // fyi, this doesn't really happen in prod often
    //       log.info(s"[persistRawKeeps] Failed bulk. Trying one at a time")
    //       val singleAttempt = db.readWrite { implicit session =>
    //         rawKeepGroup.toList.map { rawKeep =>
    //           rawKeep -> rawKeepRepo.insertOne(rawKeep)
    //         }
    //       }
    //       val (_, failuresWithRaws) = singleAttempt.partition(_._2.isSuccess)
    //       val failedUrls = failuresWithRaws.map(_._1.url)
    //       if (failedUrls.nonEmpty) {
    //         airbrake.notify(AirbrakeError(message = Some(s"failed to persist ${failedUrls.size} raw keeps: ${failedUrls mkString ","}"), userId = Some(userId)))
    //       }
    //     }
    //   }
    //   rawKeepImporterPlugin.processKeeps(broadcastToOthers = true)
    // }
  }
}

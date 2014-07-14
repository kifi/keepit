package com.keepit.commanders

import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.scraper.ScrapeSchedulerPlugin
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier

import com.keepit.common.time._

import com.google.inject.{ Provider, Inject, Singleton }
import scala.util.{ Failure, Success, Try }
import com.keepit.heimdal.HeimdalContext
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import play.api.Plugin
import akka.actor.ActorSystem
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import scala.concurrent.duration._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.json.Json
import com.keepit.search.SearchServiceClient

private case object ProcessKeeps
private class RawKeepImporterActor @Inject() (
    db: Database,
    rawKeepRepo: RawKeepRepo,
    bookmarkInternerProvider: Provider[KeepInterner],
    keepRepo: KeepRepo,
    uriRepo: NormalizedURIRepo,
    userValueRepo: UserValueRepo,
    airbrake: AirbrakeNotifier,
    urlRepo: URLRepo,
    scraper: ScrapeSchedulerPlugin,
    keptAnalytics: KeepingAnalytics,
    collectionRepo: CollectionRepo,
    kifiInstallationRepo: KifiInstallationRepo,
    bookmarksCommanderProvider: Provider[KeepsCommander],
    searchClient: SearchServiceClient,
    clock: Clock) extends FortyTwoActor(airbrake) with Logging {

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
    db.readOnlyReplica { implicit session =>
      rawKeepRepo.getOldUnprocessed(batchSize, clock.now.minusMinutes(20))
    }
  }

  def processBatch(rawKeeps: Seq[RawKeep], reason: String): Unit = {
    log.info(s"[RawKeepImporterActor] Processing ($reason) ${rawKeeps.length} keeps")

    rawKeeps.groupBy(rk => (rk.userId, rk.importId, rk.source, rk.installationId)).map {
      case ((userId, importIdOpt, source, installationId), rawKeepGroup) =>
        val context = importIdOpt.map(importId => getHeimdalContext(userId, importId)).flatten.getOrElse(HeimdalContext.empty)
        val rawBookmarks = rawKeepGroup.map { rk =>
          val canonical = rk.originalJson.map(json => (json \ Normalization.CANONICAL.scheme).asOpt[String]).flatten
          val openGraph = rk.originalJson.map(json => (json \ Normalization.OPENGRAPH.scheme).asOpt[String]).flatten
          RawBookmarkRepresentation(title = rk.title, url = rk.url, isPrivate = rk.isPrivate, canonical = canonical, openGraph = openGraph)
        }
        val (successes, failures) = bookmarkInternerProvider.get.internRawBookmarks(rawBookmarks, userId, source, mutatePrivacy = true)(context)
        val rawKeepByUrl = rawKeepGroup.map(rk => rk.url -> rk).toMap

        val failuresRawKeep = failures.map(s => rawKeepByUrl.get(s.url)).flatten.toSet
        val successesRawKeep = rawKeepGroup.filterNot(v => failuresRawKeep.contains(v))

        if (failuresRawKeep.nonEmpty) {
          db.readWriteBatch(failuresRawKeep.toSeq) {
            case (session, rk) =>
              rawKeepRepo.setState(rk.id.get, RawKeepStates.FAILED)(session)
          }
        }

        if (successes.nonEmpty) {
          if (source == KeepSource.bookmarkImport && installationId.isDefined) {
            // User selected to import Léo
            val tagName = db.readOnlyReplica { implicit session =>
              "Imported" + kifiInstallationRepo.getOpt(installationId.get).map(v => s" from ${v.userAgent.name}").getOrElse("")
            }
            val tag = bookmarksCommanderProvider.get.getOrCreateTag(userId, tagName)(context)
            bookmarksCommanderProvider.get.addToCollection(tag.id.get, successes, false)(context)
          }

          /* The strategy here is to go through all the keeps, grabbing their tags, and generating a list
         * of keeps per tag. That way applying the tag to the keeps is much more efficient, since
         * bookmarksCommander.addToCollection is super expensive.
         */
          val tagIdToKeeps = scala.collection.mutable.Map.empty[Id[Collection], scala.collection.mutable.Buffer[Keep]]

          successes.foreach { keep =>
            val allTagIdsForThisKeep = rawKeepByUrl.get(keep.url).flatMap { rk =>
              rk.tagIds.map { tags =>
                tags.split(",").toSeq.filter(_.length > 0).map { c => Try(c.toLong).map(Id[Collection]).toOption }.flatten
              }
            }.getOrElse(Seq.empty)

            allTagIdsForThisKeep.map { tagId =>
              val keepsList = tagIdToKeeps.get(tagId).getOrElse(scala.collection.mutable.Buffer.empty)
              keepsList.append(keep)
              tagIdToKeeps.put(tagId, keepsList)
            }
          }

          tagIdToKeeps.map {
            case (tagId, keeps) =>
              // Make sure tag actually exists still
              Try(bookmarksCommanderProvider.get.addToCollection(tagId, keeps, false)(context)) match {
                case Success(r) => // yay!
                case Failure(e) =>
                  log.info(s"[RawKeepImporterActor] Had problems applying tagId $tagId to ${keeps.length} keeps. Moving along.")
              }
          }

          //the bookmarks list may be very large!
          searchClient.updateURIGraph()

          db.readWriteBatch(successesRawKeep) {
            case (session, rk) =>
              rawKeepRepo.setState(rk.id.get, RawKeepStates.IMPORTED)(session)
          }
        }
        log.info(s"[RawKeepImporterActor] Interned ${successes.length + failures.length} keeps. ${successes.length} successes, ${failures.length} failures.")

        val (doneOpt, totalOpt) = db.readOnlyMaster { implicit session =>
          (userValueRepo.getValueStringOpt(userId, "bookmark_import_done").map(_.toInt),
            userValueRepo.getValueStringOpt(userId, "bookmark_import_total").map(_.toInt))
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
                userValueRepo.setValue(userId, "bookmark_import_done", done + rawKeepGroup.length)
              }
            case _ =>
          }
        }

    }
  }

  def getHeimdalContext(userId: Id[User], importId: String): Option[HeimdalContext] = {
    Try {
      db.readOnlyMaster { implicit session =>
        userValueRepo.getValueStringOpt(userId, s"bookmark_import_${importId}_context")
      }.map { jsonStr =>
        Json.fromJson[HeimdalContext](Json.parse(jsonStr)).asOpt
      }.flatten
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
    if (serviceDiscovery.isLeader()) {
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. I'm leader.")
      actor.ref ! ProcessKeeps
    } else if (broadcastToOthers) {
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. Sending to leader.")
      shoeboxServiceClient.triggerRawKeepImport()
    }
  }

  override def onStart() {
    scheduleTaskOnLeader(system, 70 seconds, 1 minute, actor.ref, ProcessKeeps)
    super.onStart()
  }
}


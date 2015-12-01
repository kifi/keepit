package com.keepit.commanders

import java.util.concurrent.{ Callable, TimeUnit }

import com.google.common.cache.{ Cache, CacheBuilder }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db._
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.db.slick._
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.core._

import com.keepit.common.time._

import com.google.inject.{ Provider, Inject, Singleton }
import com.keepit.rover.RoverServiceClient
import scala.concurrent.ExecutionContext
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
    tagHelper: KeepTagImportHelper,
    implicit val executionContext: ExecutionContext) extends FortyTwoActor(airbrake) with Logging {

  private val batchSize = 500
  def receive = {
    case ProcessKeeps =>
      log.info(s"[RawKeepImporterActor] Running raw keep process")
      val activeBatch = fetchActiveBatch()
      processBatch(activeBatch, "active")
      val oldBatchSize = if (activeBatch.length <= 10) {
        val oldBatch = fetchOldBatch()
        processBatch(oldBatch, "old")
        oldBatch.length
      } else 0

      val totalProcessed = activeBatch.length + oldBatchSize
      if (totalProcessed >= batchSize) { // batch was non-empty, so there may be more to process
        log.info(s"[RawKeepImporterActor] Looks like there may be more. Self-calling myself.")
        self ! ProcessKeeps
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
      rawKeepRepo.getOldUnprocessed(batchSize, clock.now.minusMinutes(5))
    }
  }

  private def processBatch(rawKeeps: Seq[RawKeep], reason: String): Unit = {
    log.info(s"[RawKeepImporterActor] Processing ($reason) ${rawKeeps.length} keeps")

    rawKeeps.groupBy(rk => (rk.userId, rk.importId, rk.source, rk.installationId, rk.libraryId)).foreach {
      case ((userId, importIdOpt, source, installationId, libraryId), rawKeepGroup) =>

        val ctx = importIdOpt.flatMap(importId => getHeimdalContext(userId, importId)).getOrElse(HeimdalContext.empty)

        val successes = internKeeps(userId, libraryId.get, rawKeepGroup, source, ctx)

        if (successes.nonEmpty) {
          //process tags: create collections, put keeps into collections.
          tagHelper.process(RawKeepGroupImportContext(userId, source, installationId, rawKeepGroup, successes, ctx))
        }
    }
  }

  private def parseRawBookmarksFromJson(rawKeepGroup: Seq[RawKeep]) = {
    rawKeepGroup.map { rk =>
      val canonical = rk.originalJson.flatMap(json => (json \ Normalization.CANONICAL.scheme).asOpt[String])
      val openGraph = rk.originalJson.flatMap(json => (json \ Normalization.OPENGRAPH.scheme).asOpt[String])
      val attribution = RawKeep.extractKeepSourceAttribtuion(rk)
      RawBookmarkRepresentation(title = rk.title, url = rk.url, canonical = canonical, openGraph = openGraph, isPrivate = None, keptAt = rk.createdDate, sourceAttribution = attribution)
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
      db.readWriteBatch(failuresRawKeep.toSeq) {
        case (session, rk) =>
          rawKeepRepo.setState(rk.id.get, RawKeepStates.FAILED)(session)
      }
    }

    if (successesRawKeep.nonEmpty) {
      // mark done
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
    if (serviceDiscovery.isLeader()) {
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. I'm leader.")
      actor.ref ! ProcessKeeps
    } else if (broadcastToOthers) {
      log.info(s"[RawKeepImporterPluginImpl] Need to process raw keeps. Sending to leader.")
      shoeboxServiceClient.triggerRawKeepImport()
    }
  }

  override def onStart() {
    scheduleTaskOnOneMachine(system, 127 seconds, 71 seconds, actor.ref, ProcessKeeps, ProcessKeeps.getClass.getSimpleName)
    super.onStart()
  }
}

case class RawKeepGroupImportContext(userId: Id[User], source: KeepSource, installationIdOpt: Option[ExternalId[KifiInstallation]], rawKeepGroup: Seq[RawKeep], successImports: Seq[Keep], context: HeimdalContext)

@Singleton
class KeepTagImportHelper @Inject() (
    db: Database,
    keepsCommanderProvider: Provider[KeepCommander],
    kifiInstallationRepo: KifiInstallationRepo) extends Logging {

  def process(rawKeepGroupImportContext: RawKeepGroupImportContext): Unit = {
    val (userId, source, installationIdOpt, rawKeepGroup, successImports, context) = RawKeepGroupImportContext.unapply(rawKeepGroupImportContext).get

    if (source == KeepSource.bookmarkImport && installationIdOpt.isDefined) {
      markKeepsAsImported(userId, successImports, installationIdOpt.get)(context)
    }

    val collectionIdToKeeps: Map[Id[Collection], mutable.Buffer[Keep]] = {
      val keepTagMap: Map[String, Collection] = genLowerCaseTagNameToCollectionMap(userId, rawKeepGroup)(context)
      val rawKeepByUrl = rawKeepGroup.map(rk => rk.url -> rk).toMap
      genCollectionIdToKeeps(keepTagMap, successImports, rawKeepByUrl)
    }

    saveKeepsToCollections(collectionIdToKeeps)(context)

  }

  private def genLowerCaseTagNameToCollectionMap(userId: Id[User], rawKeepGroup: Seq[RawKeep])(context: HeimdalContext): Map[String, Collection] = {
    // create a set of all tags
    val keepTagNamesMap = scala.collection.mutable.Map.empty[String, String]
    rawKeepGroup.foreach { rk =>
      rk.keepTags.foreach { tagNames =>
        tagNames.as[Seq[String]].foreach { tagName =>
          keepTagNamesMap += (tagName.toLowerCase -> tagName)
        }
      }
    }
    // map tagNames to keepTags
    val keepTagMap = keepTagNamesMap.values.toSet.map { tagName: String =>
      val keepTag = keepsCommanderProvider.get.getOrCreateTag(userId, tagName)(context)
      (tagName.toLowerCase, keepTag)
    }.toMap

    keepTagMap
  }

  private def genCollectionIdToKeeps(keepTagMap: Map[String, Collection], successes: Seq[Keep], rawKeepByUrl: Map[String, RawKeep]): Map[Id[Collection], mutable.Buffer[Keep]] = {
    val tagIdToKeeps = mutable.Map.empty[Id[Collection], mutable.Buffer[Keep]]

    successes.foreach { keep =>
      val allTagIdsForThisKeep = rawKeepByUrl.get(keep.url).flatMap { rk =>

        val tagIdsFromKeepTags = rk.keepTags.map { tagArray =>
          tagArray.as[Seq[String]].flatMap { tagName =>
            keepTagMap.get(tagName.toLowerCase).map(_.id.get)
          }
        }

        tagIdsFromKeepTags

      }.getOrElse(Seq.empty)

      allTagIdsForThisKeep.map { tagId =>
        val keepsList = tagIdToKeeps.getOrElse(tagId, mutable.Buffer.empty)
        keepsList.append(keep)
        tagIdToKeeps.put(tagId, keepsList)
      }
    }

    tagIdToKeeps.toMap
  }

  private def markKeepsAsImported(userId: Id[User], keeps: Seq[Keep], installationId: ExternalId[KifiInstallation])(context: HeimdalContext): Unit = {
    val tagName = db.readOnlyReplica { implicit session =>
      "Imported" + kifiInstallationRepo.getOpt(installationId).map(v => s" from ${v.userAgent.name}").getOrElse("")
    }
    val tag = keepsCommanderProvider.get.getOrCreateTag(userId, tagName)(context)
    keepsCommanderProvider.get.addToCollection(tag.id.get, keeps, updateIndex = false)(context)
  }

  private def saveKeepsToCollections(collectionIdToKeeps: Map[Id[Collection], Seq[Keep]])(context: HeimdalContext) = {
    collectionIdToKeeps.foreach {
      case (tagId, keeps) =>
        // Make sure tag actually exists still
        Try(keepsCommanderProvider.get.addToCollection(tagId, keeps, false)(context)) match {
          case Success(r) => // yay!
          case Failure(e) =>
            log.warn(s"[RawKeepImporterActor] Had problems applying tagId $tagId to ${keeps.length} keeps. Moving along.", e)
        }
    }
  }

}

package com.keepit.commander

import com.google.inject.Inject
import com.keepit.common.core._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.concurrent.FutureHelpers
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.common.performance._
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.{ SearchHitReportCache, SearchHitReport, SearchHitReportKey }
import com.keepit.model._
import com.keepit.model.helprank.{ UserBookmarkClicksRepo, KeepDiscoveryRepo, ReKeepRepo }
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class HelpRankCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    clock: Clock,
    kifiHitCache: SearchHitReportCache,
    userKeepInfoRepo: UserBookmarkClicksRepo,
    keepDiscoveryRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    elizaClient: ElizaServiceClient,
    shoeboxClient: ShoeboxServiceClient) extends Logging {

  def processSearchHitAttribution(kifiHit: SearchHitReport): Future[Unit] = {
    val discoverer = kifiHit.userId
    val keepers = kifiHit.keepers
    if (kifiHit.isOwnKeep || keepers.isEmpty) {
      db.readWriteAsync { implicit rw =>
        userKeepInfoRepo.increaseCounts(discoverer, kifiHit.uriId, true)
      }
    } else {
      implicit val dca = TransactionalCaching.Implicits.directCacheAccess
      kifiHitCache.get(SearchHitReportKey(discoverer, kifiHit.uriId)) match { // simple throttling
        case Some(hit) =>
          Future.successful(Unit)
        case None =>
          kifiHitCache.set(SearchHitReportKey(discoverer, kifiHit.uriId), kifiHit)
          shoeboxClient.getUserIdsByExternalIds(keepers) map { keeperIds =>
            keeperIds foreach { keeperId =>
              db.readWrite { implicit rw =>
                userKeepInfoRepo.increaseCounts(keeperId, kifiHit.uriId, false)
              }
              shoeboxClient.getBookmarkByUriAndUser(kifiHit.uriId, keeperId) foreach { keepOpt =>
                keepOpt match {
                  case None =>
                  case Some(keep) =>
                    val saved = db.readWrite { implicit rw =>
                      keepDiscoveryRepo.save(KeepDiscovery(hitUUID = kifiHit.uuid, numKeepers = keepers.length, keeperId = keeperId, keepId = keep.id.get, uriId = keep.uriId, origin = Some(kifiHit.origin)))
                    }
                }
              }
            }
          }
      }
    }
  }

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = {
    SafeFuture {
      val builder = collection.mutable.ArrayBuilder.make[Keep]
      newKeeps.foreach { keep =>
        val rekeeps = searchAttribution(userId, keep)
        if (rekeeps.isEmpty) {
          builder += keep
        }
      }
      builder.result
    } flatMap { remainders =>
      val chunkCB = { idx: Int => () }
      FutureHelpers.chunkySequentialExec(remainders, 50, chunkCB) { keep: Keep =>
        chatAttribution(userId, keep)
      }
    }
  }

  private def searchAttribution(userId: Id[User], keep: Keep): Seq[ReKeep] = {
    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    kifiHitCache.get(SearchHitReportKey(userId, keep.uriId)) map { hit =>
      val res = db.readWrite { implicit rw =>
        keepDiscoveryRepo.getDiscoveriesByUUID(hit.uuid) collect {
          case c if rekeepRepo.getReKeep(c.keeperId, c.uriId, userId).isEmpty =>
            val rekeep = ReKeep(keeperId = c.keeperId, keepId = c.keepId, uriId = c.uriId, srcUserId = userId, srcKeepId = keep.id.get, attributionFactor = c.numKeepers)
            rekeepRepo.save(rekeep) tap { saved =>
            }
        }
      }
      res
    } getOrElse Seq.empty
  }

  private def chatAttribution(userId: Id[User], keep: Keep): Future[Unit] = {
    elizaClient.keepAttribution(userId, keep.uriId) map { otherStarters =>
      otherStarters.foreach { chatUserId =>
        db.readOnlyMaster { implicit ro =>
          rekeepRepo.getReKeep(chatUserId, keep.uriId, userId)
        } match {
          case Some(rekeep) =>
          case None =>
            shoeboxClient.getBookmarkByUriAndUser(keep.uriId, chatUserId) map { keepOpt =>
              keepOpt foreach { chatUserKeep =>
                try {
                  db.readWrite { implicit rw =>
                    val discovery = KeepDiscovery(hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = chatUserId, keepId = chatUserKeep.id.get, uriId = keep.uriId, origin = Some("messaging")) // todo(ray): None for uuid
                    val savedDiscovery = keepDiscoveryRepo.save(discovery)
                    val rekeep = ReKeep(keeperId = chatUserId, keepId = chatUserKeep.id.get, uriId = keep.uriId, srcUserId = userId, srcKeepId = keep.id.get, attributionFactor = 1)
                    val saved = rekeepRepo.save(rekeep)
                  }
                } catch {
                  case t: Throwable =>
                    airbrake.notify(s"[chatAttribution($userId)] failed to process $keep; exception: $t", t)
                }
              }
            }
        }
      }
    }
  }

  def getKeepAttributionInfo(userId: Id[User]): UserKeepAttributionInfo = {
    db.readOnlyMaster { implicit session =>
      val discoveryCount = keepDiscoveryRepo.getDiscoveryCountByKeeper(userId)
      val (rekeepCount, rekeepTotalCount) = userKeepInfoRepo.getReKeepCounts(userId)
      val (uniqueKeepsClicked, totalClicks) = userKeepInfoRepo.getClickCounts(userId)
      UserKeepAttributionInfo(userId, discoveryCount, rekeepCount, rekeepTotalCount, uniqueKeepsClicked, totalClicks) tap { info =>
      }
    }
  }

  def getHelpRankInfo(uriIds: Seq[Id[NormalizedURI]]): Future[Seq[HelpRankInfo]] = {
    val uriIdSet = uriIds.toSet
    if (uriIdSet.size != uriIds.length) {
      log.warn(s"[getHelpRankInfo] (duplicates!) uriIds(len=${uriIds.length}):${uriIds.mkString(",")} idSet(sz=${uriIdSet.size}):${uriIdSet.mkString(",")}")
    }
    val discMapF = db.readOnlyMasterAsync { implicit ro =>
      keepDiscoveryRepo.getDiscoveryCountsByURIs(uriIdSet)
    }
    val rkMapF = db.readOnlyMasterAsync { implicit ro =>
      rekeepRepo.getReKeepCountsByURIs(uriIdSet)
    }
    for {
      discMap <- discMapF
      rkMap <- rkMapF
    } yield {
      uriIds.toSeq.map { uriId =>
        HelpRankInfo(uriId, discMap.getOrElse(uriId, 0), rkMap.getOrElse(uriId, 0))
      }
    }
  }

  def getUserWithMostClickedKeeps(userIds: Set[Id[User]]): Option[Id[User]] = {
    if (userIds.size > 1) db.readOnlyReplica { implicit session => keepDiscoveryRepo.getDiscoveryCountsByKeeper(userIds).maxByOpt(_._2).map(_._1) }
    else if (userIds.size == 1) Some(userIds.head)
    else None
  }

}

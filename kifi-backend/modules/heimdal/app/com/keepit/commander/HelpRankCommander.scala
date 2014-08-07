package com.keepit.commander

import com.google.inject.Inject
import com.keepit.common.KestrelCombinator
import com.keepit.common.akka.SafeFuture
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.concurrent.{ FutureHelpers, ExecutionContext }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import com.keepit.eliza.ElizaServiceClient
import com.keepit.heimdal.SanitizedKifiHit
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
    kifiHitCache: KifiHitCache,
    userKeepInfoRepo: UserBookmarkClicksRepo,
    keepDiscoveryRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    elizaClient: ElizaServiceClient,
    shoeboxClient: ShoeboxServiceClient) extends Logging {

  def processKifiHit(discoverer: Id[User], kifiHit: SanitizedKifiHit): Future[Unit] = {
    log.info(s"[kifiHit($discoverer)] hit=$kifiHit")
    db.readWriteAsync { implicit rw =>
      val keepers = kifiHit.context.keepers
      if (kifiHit.context.isOwnKeep || kifiHit.context.isPrivate || keepers.isEmpty) userKeepInfoRepo.increaseCounts(discoverer, kifiHit.uriId, true)
      else {
        kifiHitCache.get(KifiHitKey(discoverer, kifiHit.uriId)) match { // simple throttling
          case Some(hit) =>
            log.warn(s"[kifiHit($discoverer,${kifiHit.uriId})] already recorded kifiHit ($hit) for user within threshold -- skip")
          case None =>
            kifiHitCache.set(KifiHitKey(discoverer, kifiHit.uriId), kifiHit)
            shoeboxClient.getUserIdsByExternalIds(keepers) map { keeperIds =>
              keeperIds foreach { keeperId =>
                userKeepInfoRepo.increaseCounts(keeperId, kifiHit.uriId, false)
                shoeboxClient.getBookmarkByUriAndUser(kifiHit.uriId, keeperId) map { keepOpt =>
                  keepOpt match {
                    case None =>
                      log.warn(s"[kifiHit($discoverer,${kifiHit.uriId},${keepers.mkString(",")})] keep not found for keeperId=$keeperId") // move on
                    case Some(keep) =>
                      val saved = keepDiscoveryRepo.save(KeepDiscovery(hitUUID = kifiHit.uuid, numKeepers = keepers.length, keeperId = keeperId, keepId = keep.id.get, uriId = keep.uriId, origin = Some(kifiHit.origin)))
                      log.info(s"[kifiHit($discoverer, ${kifiHit.uriId}, ${keepers.mkString(",")})] saved $saved")
                  }
                }
              }
            }
        }
      }
    }
  }

  def processKeepAttribution(userId: Id[User], newKeeps: Seq[Keep]): Future[Unit] = {
    log.info(s"[keepAttribution($userId)] newKeeps=${newKeeps.map(k => s"Keep(${k.id},${k.uriId},${k.url})")}")
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
      FutureHelpers.sequentialExec(remainders) { keep =>
        chatAttribution(userId, keep)
      }
    }
  }

  private def searchAttribution(userId: Id[User], keep: Keep): Seq[ReKeep] = {
    log.info(s"[searchAttribution($userId)] keep=$keep")
    implicit val dca = TransactionalCaching.Implicits.directCacheAccess
    kifiHitCache.get(KifiHitKey(userId, keep.uriId)) map { hit =>
      val res = db.readWrite { implicit rw =>
        keepDiscoveryRepo.getDiscoveriesByUUID(hit.uuid) collect {
          case c if rekeepRepo.getReKeep(c.keeperId, c.uriId, userId).isEmpty =>
            val rekeep = ReKeep(keeperId = c.keeperId, keepId = c.keepId, uriId = c.uriId, srcUserId = userId, srcKeepId = keep.id.get, attributionFactor = c.numKeepers)
            rekeepRepo.save(rekeep) tap { saved =>
              log.info(s"[searchAttribution($userId,${keep.uriId})] rekeep=$saved; click=$c")
            }
        }
      }
      res
    } getOrElse Seq.empty
  }

  private def chatAttribution(userId: Id[User], keep: Keep): Future[Unit] = {
    elizaClient.keepAttribution(userId, keep.uriId) map { otherStarters =>
      log.info(s"[chatAttribution($userId,${keep.uriId})] otherStarters=$otherStarters")
      otherStarters.foreach { chatUserId =>
        db.readWrite { implicit rw =>
          rekeepRepo.getReKeep(chatUserId, keep.uriId, userId) match {
            case Some(rekeep) =>
              log.info(s"[chatAttribution($userId,${keep.uriId},$chatUserId)] rekeep=$rekeep already exists. Skipped.")
            case None =>
              shoeboxClient.getBookmarkByUriAndUser(keep.uriId, chatUserId) map { keepOpt =>
                log.info(s"[chatAttribution($userId,${keep.uriId},$chatUserId)] getBookmarkByUriAndUser=$keepOpt")
                keepOpt foreach { chatUserKeep =>
                  val discovery = KeepDiscovery(hitUUID = ExternalId[ArticleSearchResult](), numKeepers = 1, keeperId = chatUserId, keepId = chatUserKeep.id.get, uriId = keep.uriId, origin = Some("messaging")) // todo(ray): None for uuid
                  val savedDiscovery = keepDiscoveryRepo.save(discovery)
                  val rekeep = ReKeep(keeperId = chatUserId, keepId = chatUserKeep.id.get, uriId = keep.uriId, srcUserId = userId, srcKeepId = keep.id.get, attributionFactor = 1)
                  val saved = rekeepRepo.save(rekeep)
                  log.info(s"[chatAttribution($userId,${keep.uriId})] rekeep=$saved; discovery=$savedDiscovery")
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
        log.info(s"[getKeepAttribution($userId)] info=$info")
      }
    }
  }

}

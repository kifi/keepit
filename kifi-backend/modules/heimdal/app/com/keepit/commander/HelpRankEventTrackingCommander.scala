package com.keepit.commander

import com.google.inject.Inject
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.cache.TransactionalCaching
import com.keepit.common.logging.{ LogPrefix, Logging }
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.model.helprank.{ KeepDiscoveryRepo }
import com.keepit.search.ArticleSearchResult
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.logging.Logging.LoggerWithPrefix

import scala.concurrent.Future

object HelpRankEventTrackingCommander {
  implicit class HeimdalContextWrapper(underlying: HeimdalContext) {
    // helpers/workarounds -- until we have a stronger contract for events
    def getIdOpt[T](key: String): Option[Id[T]] = underlying.get[Double](key) map { id => Id[T](id.toLong) }
    def getIdsOpt[T](key: String): Option[Seq[Id[T]]] = underlying.getSeq[Double](key).map { ids => ids.map(id => Id[T](id.toLong)) }
  }
}

class HelpRankEventTrackingCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    kifiHitCache: SearchHitReportCache,
    shoebox: ShoeboxServiceClient,
    keepDiscoveryRepo: KeepDiscoveryRepo) extends Logging {

  import HelpRankEventTrackingCommander._

  def userClickedFeedItem(userEvent: UserEvent): Future[Unit] = {
    val valuesOpt = for {
      userId <- userEvent.context.getIdOpt[User]("userId")
      uriId <- userEvent.context.getIdOpt[NormalizedURI]("uriId")
    } yield {
      val keeperIds = userEvent.context.getIdsOpt[User]("keepers").getOrElse(Seq.empty[Id[User]])
      (userId, uriId, keeperIds)
    }

    valuesOpt match {
      case None =>
        val msg = s"[userClickedFeedItem] failed to process $userEvent"
        log.error(msg)
        Future.failed(new IllegalArgumentException(msg))
      case Some((userId, uriId, keeperIds)) =>
        implicit val logPrefix = LogPrefix(s"userClickedFeedItem($userId,$uriId)")
        if (keeperIds.isEmpty) {
          log.infoP("no keepers; skipped")
          Future.successful(())
        } else {
          log.infoP(s"keepers=${keeperIds.mkString(",")}")
          val hitUUID = ExternalId[ArticleSearchResult]()
          val ts = userEvent.time
          val numKeepers = keeperIds.size
          implicit val dca = TransactionalCaching.Implicits.directCacheAccess
          kifiHitCache.get(SearchHitReportKey(userId, uriId)) match {
            case Some(hit) =>
              log.warnP(s"already recorded hit ($hit) for user within threshold -- skipped")
              Future.successful(())
            case None =>
              val res = shoebox.getBasicUsers(keeperIds) flatMap { userMap =>
                val extKeeperIds = keeperIds.collect { case id if userMap.get(id).isDefined => userMap(id).externalId }
                val sanitizedHit = SearchHitReport(userId, uriId, false, extKeeperIds, "feed", hitUUID)
                kifiHitCache.set(SearchHitReportKey(userId, uriId), sanitizedHit)
                log.infoP(s"key=${SearchHitReportKey(userId, uriId)} cached=${kifiHitCache.get(SearchHitReportKey(userId, uriId))}")
                val futures = keeperIds map { keeperId =>
                  shoebox.getBookmarkByUriAndUser(uriId, keeperId) map { keepOpt =>
                    keepOpt match {
                      case None =>
                        log.warnP(s"keep not found for keeperId=$keeperId")
                      case Some(keep) =>
                        val saved = db.readWrite { implicit rw =>
                          keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = hitUUID, numKeepers = numKeepers, keeperId = keeperId, keepId = keep.id.get, uriId = uriId, origin = Some("feed")))
                        }
                        log.infoP(s"saved $saved")
                    }
                  }
                }
                Future.sequence(futures)
              }
              res map { _ => () }
          }
        }
    }
  }

}

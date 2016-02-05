package com.keepit.commander

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.SearchHitReportCache
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.db.Id
import com.keepit.model.helprank.{ ReKeepRepo, KeepDiscoveryRepo, UserBookmarkClicksRepo }
import scala.collection.mutable
import scala.concurrent.{ Promise, Future }
import com.keepit.model.UserBookmarkClicks
import com.keepit.common.concurrent.FutureHelpers
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{ Success, Failure }

class AttributionCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    userBookmarkClicksRepo: UserBookmarkClicksRepo,
    keepClicksRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    kifiHitCache: SearchHitReportCache) extends Logging {

  // potentially expensive -- admin only
  def getUserReKeepsByDegree(keepIds: Seq[KeepIdInfo], n: Int = 3): Map[Id[Keep], Seq[Seq[Id[User]]]] = {
    require(keepIds.size >= 0 && keepIds.size <= 50, s"getUserReKeepsByDegree() illegal argument keepIds.size=${keepIds.size}")
    if (keepIds.isEmpty) Map.empty[Id[Keep], Seq[Seq[Id[User]]]]
    else {
      val userKeeps = keepIds.groupBy(_.userId)

      // sequential -- no need to overload shoebox
      val res = userKeeps.map {
        case (userId, keeps) =>
          keeps.toSeq.map { keep =>
            keep.keepId -> getReKeepsByDegree(userId, keep.keepId, n).map { case (userIdsByDeg, _) => userIdsByDeg }
          }
      }.flatten.toMap
      res
    }
  }

  // potentially expensive -- admin only for now; will be called infrequently (cron job) later
  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep], n: Int = 3): Seq[(Seq[Id[User]], Seq[Id[Keep]])] = {
    require(n > 1 && n < 5, s"getReKeepsByDegree($keeperId, $keepId) illegal argument (degree=$n)")
    val rekeepsByDeg = new Array[Set[Id[Keep]]](n)
    rekeepsByDeg(0) = Set(keepId)
    val usersByDeg = new Array[Set[Id[User]]](n)
    usersByDeg(0) = Set(keeperId)

    val accKeepIds = new mutable.HashSet[Id[Keep]]()
    accKeepIds += keepId
    val accUserIds = new mutable.HashSet[Id[User]]()
    accUserIds += keeperId

    for (i <- 1 until n) {
      if (rekeepsByDeg(i - 1).isEmpty) {
        rekeepsByDeg(i) = Set.empty[Id[Keep]]
        usersByDeg(i) = Set.empty[Id[User]]
      } else {
        val currRekeeps = db.readOnlyReplica { implicit r => rekeepRepo.getReKeeps(rekeepsByDeg(i - 1)) }
        val mergedKeepIds = currRekeeps.valuesIterator.foldLeft(Set.empty[Id[Keep]]) { (a, c) => a ++ c.map(_.srcKeepId) }
        val mergedUserIds = currRekeeps.valuesIterator.foldLeft(Set.empty[Id[User]]) { (a, c) => a ++ c.map(_.srcUserId) }
        rekeepsByDeg(i) = mergedKeepIds -- accKeepIds
        usersByDeg(i) = mergedUserIds -- accUserIds
        accKeepIds ++= mergedKeepIds
        accUserIds ++= mergedUserIds
      }
    }

    usersByDeg.map { _.toSeq } zip rekeepsByDeg.map { _.toSeq }
  }

  def updateUserReKeepStats(userId: Id[User], n: Int = 3): Future[Seq[UserBookmarkClicks]] = { // expensive -- admin only
    val rekeepCountsF = db.readOnlyReplicaAsync { implicit ro =>
      rekeepRepo.getAllReKeepsByKeeper(userId).groupBy(_.keepId).map {
        case (keepId, rekeeps) =>
          (keepId, rekeeps.head.uriId) -> rekeeps.length
      }
    }
    rekeepCountsF map { rekeepCounts =>
      val rekeepStats = rekeepCounts.map {
        case ((keepId, uriId), rekeepCount) =>
          (keepId, uriId) -> (rekeepCount, getReKeepsByDegree(userId, keepId, n).map(_._1).flatten.length - 1) // exclude self
      }

      val res = db.readWrite { implicit rw =>
        rekeepStats.map {
          case ((keepId, uriId), (rkCount, aggCount)) =>
            userBookmarkClicksRepo.getByUserUri(userId, uriId) match {
              case None =>
                userBookmarkClicksRepo.save(UserBookmarkClicks(userId = userId, uriId = uriId, selfClicks = 0, otherClicks = 0, rekeepCount = rkCount, rekeepTotalCount = aggCount, rekeepDegree = n))
              case Some(bookmarkClick) =>
                userBookmarkClicksRepo.save(bookmarkClick.copy(rekeepCount = rkCount, rekeepTotalCount = aggCount, rekeepDegree = n))
            }
        }.toSeq
      }

      res
    }
  }

  def updateUsersReKeepStats(keepers: Seq[Id[User]], n: Int = 3): Future[Seq[Seq[UserBookmarkClicks]]] = { // expensive -- admin only
    val promise = Promise[Seq[Seq[UserBookmarkClicks]]]
    val builder = mutable.ArrayBuilder.make[Seq[UserBookmarkClicks]]
    val chunkCB: Int => Unit = { idx => () }
    val execF = FutureHelpers.chunkySequentialExec(keepers, 50, chunkCB) { keeperId: Id[User] =>
      updateUserReKeepStats(keeperId, n) map { res =>
        builder += res
      }
    }
    execF onComplete {
      case Success(_) => promise.success(builder.result())
      case Failure(t) => promise.failure(t)
    }
    promise.future
  }

  def updateAllReKeepStats(n: Int = 3): Future[Seq[Seq[UserBookmarkClicks]]] = { // expensive -- admin only
    val keepersF = db.readOnlyReplicaAsync { implicit ro =>
      rekeepRepo.getAllKeepers()
    }
    keepersF flatMap { keepers =>
      updateUsersReKeepStats(keepers)
    }
  }

}

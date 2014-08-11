package com.keepit.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.model._
import com.keepit.common.logging.Logging
import com.keepit.common.performance._
import com.keepit.common.db.Id
import scala.collection.mutable
import com.keepit.common.db.slick.Database.Replica
import scala.concurrent.Future
import com.keepit.common.concurrent.ExecutionContext._
import scala.Some
import com.keepit.model.UserBookmarkClicks
import com.keepit.common.concurrent.FutureHelpers

class AttributionCommander @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    keepRepo: KeepRepo,
    userBookmarkClicksRepo: UserBookmarkClicksRepo,
    keepClicksRepo: KeepDiscoveryRepo,
    rekeepRepo: ReKeepRepo,
    kifiHitCache: KifiHitCache) extends Logging {

  implicit val execCtx = fj

  // potentially expensive -- admin only
  def getUserReKeepsByDegree(keepIds: Set[Id[Keep]], n: Int = 3): Map[Id[Keep], Seq[Set[Id[User]]]] = timing(s"getUserReKeepsByDegree(#keeps=${keepIds.size},$n)") {
    require(keepIds.size >= 0 && keepIds.size <= 50, s"getUserReKeepsByDegree() illegal argument keepIds.size=${keepIds.size}")
    if (keepIds.isEmpty) Map.empty[Id[Keep], Seq[Set[Id[User]]]]
    else {
      val keeps = db.readOnlyReplica { implicit ro => keepIds.map { keepRepo.get } }
      val userKeeps = keeps.groupBy(_.userId)

      // sequential -- no need to overload shoebox
      val res = userKeeps.map {
        case (userId, keeps) =>
          keeps.toSeq.map { keep =>
            keep.id.get -> getReKeepsByDegree(userId, keep.id.get, n).map { case (userIdsByDeg, _) => userIdsByDeg }
          }
      }.flatten.toMap
      log.info(s"getUserReKeepsByDegree res=$res")
      res
    }
  }

  // potentially expensive -- admin only for now; will be called infrequently (cron job) later
  def getReKeepsByDegree(keeperId: Id[User], keepId: Id[Keep], n: Int = 3): Seq[(Set[Id[User]], Set[Id[Keep]])] = timing(s"getReKeepsByDegree($keeperId,$keepId,$n)") {
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
        log.info(s"getRKBD($keeperId,$keepId,$n) rekeepsByDeg(${i - 1})=${rekeepsByDeg(i - 1)}")
        val currRekeeps = db.readOnlyReplica { implicit r => rekeepRepo.getReKeeps(rekeepsByDeg(i - 1)) }
        val mergedKeepIds = currRekeeps.valuesIterator.foldLeft(Set.empty[Id[Keep]]) { (a, c) => a ++ c.map(_.srcKeepId) }
        val mergedUserIds = currRekeeps.valuesIterator.foldLeft(Set.empty[Id[User]]) { (a, c) => a ++ c.map(_.srcUserId) }
        rekeepsByDeg(i) = mergedKeepIds -- accKeepIds
        usersByDeg(i) = mergedUserIds -- accUserIds
        accKeepIds ++= mergedKeepIds
        accUserIds ++= mergedUserIds
        log.info(s"getRKBD($keeperId,$keepId,$n) mergedKeepIds=$mergedKeepIds mergedUserIds=$mergedUserIds accKeepIds=$accKeepIds accUserIds=$accUserIds")
      }
    }

    usersByDeg zip rekeepsByDeg
  }

}

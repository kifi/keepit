package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

object GratificationCommander {

  case class LibraryCountData(totalCount: Int, countByLibrary: Map[Id[Library], Int]) {
    val sortedCountByLibrary = countByLibrary.toList.sortWith { _._2 > _._2 }
  }

}

@Singleton
class GratificationCommander @Inject() (
    db: Database,
    libMemRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    heimdal: HeimdalServiceClient) {

  private val NUM_WEEKS_BACK = 1
  val EXPERIMENT_DEPLOY = false // only send emails to users with the GRATIFICATION_EMAIL experiment
  val MIN_FOLLOWERS = 1
  val MIN_VIEWS = 5
  val MIN_CONNECTIONS = 1

  private val remoteCallQueue = new ReactiveLock(numConcurrent = 5)

  def getLibraryFollowCounts(userId: Id[User]): LibraryCountData = {
    db.readOnlyReplica { implicit s =>
      val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
      // val cnt = libMemRepo.userRecentUniqueFollowerCounts(userId, since) // deprecated, since it gets unique followers. we currently use the total "follows" across libraries.
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since).filter { case (id, _) => libraryRepo.get(id).state == LibraryStates.ACTIVE }
      val cnt = cntMap.foldLeft[Int](0)((acc, kv) => acc + kv._2) // get total "follows" over all libraries
      LibraryCountData(cnt, cntMap)
    }
  }

  def getLibraryViewData(userId: Id[User]): Future[LibraryCountData] = {
    remoteCallQueue.withLockFuture {
      val libCountData = heimdal.getOwnerLibraryViewStats(userId).map {
        case (cnt, cntMap) =>
          db.readOnlyReplica { implicit s => LibraryCountData(cnt, cntMap.filter { case (id, count) => libraryRepo.get(id).state == LibraryStates.ACTIVE }) }
      }
      libCountData
    }
  }

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since).filter { connectionUserId => userRepo.get(connectionUserId).state == UserStates.ACTIVE }
    }
    newConnections.toSeq
  }

  def usersToSendEmailTo(): Future[Seq[Id[User]]] = {
    val userIds: Seq[Id[User]] = db.readOnlyReplica { implicit session => userRepo.getAllIds() }.toSeq // TODO: have heimdal return us the list of eligible users (in terms of library views)

    if (EXPERIMENT_DEPLOY) {
      // only send to those with the experiment (testing in production)
      Future.successful(userIds.filter { id =>
        localUserExperimentCommander.userHasExperiment(id, ExperimentType.GRATIFICATION_EMAIL)
      })
    } else {
      filterUsersWithoutData(userIds)
    }
  }

  def filterUsersWithoutData(userIds: Seq[Id[User]]): Future[Seq[Id[User]]] = {

    val idAndViewByLib: Seq[Future[(Id[User], LibraryCountData)]] = userIds.map { id =>
      val fViewsByLibrary: Future[LibraryCountData] = getLibraryViewData(id)
      fViewsByLibrary.map { x =>
        (id, x)
      } recover { case t => (id, LibraryCountData(-1, Map.empty)) } // will filter out as long as MIN_VIEWS >= 0
    }

    val fIdAndViewByLib: Future[Seq[(Id[User], LibraryCountData)]] = Future.sequence(idAndViewByLib)

    val result = fIdAndViewByLib.map { seq: Seq[(Id[User], LibraryCountData)] =>
      seq.filter {
        case (id: Id[User], viewsByLib: LibraryCountData) =>
          val totalLibFollows = getLibraryFollowCounts(id).totalCount
          val totalNewConnections = getNewConnections(id).length
          val totalLibViews = viewsByLib.totalCount
          totalLibFollows >= MIN_FOLLOWERS || totalNewConnections >= MIN_CONNECTIONS || totalLibViews >= MIN_VIEWS
      }.map {
        case (id: Id[User], _: LibraryCountData) =>
          id
      }
    }
    result
  }
}

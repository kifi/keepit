package com.keepit.commanders.emails

import com.google.inject.Inject
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.commanders.emails.GratificationCommander.LibraryCountData
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

object GratificationCommander {

  case class LibraryCountData(totalCount: Int, countByLibrary: Map[Id[Library], Int])

}

class GratificationCommander @Inject() (
    db: Database,
    libMemRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    userConnectionRepo: UserConnectionRepo,
    userRepo: UserRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    heimdal: HeimdalServiceClient) {

  private val NUM_WEEKS_BACK = 1
  val EXPERIMENT_DEPLOY = true // only send emails to users with the GRATIFICATION_EMAIL experiment
  val MIN_FOLLOWERS = 1
  val MIN_VIEWS = 5
  val MIN_CONNECTIONS = 1

  def getLibraryFollowerCounts(userId: Id[User]): LibraryCountData = {
    db.readOnlyReplica { implicit s =>
      val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
      val cnt = libMemRepo.userRecentFollowerCounts(userId, since)
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since)
      LibraryCountData(cnt, cntMap)
    }
  }

  def getLibraryViewData(userId: Id[User]): Future[LibraryCountData] = {
    heimdal.getOwnerLibraryViewStats(userId).map {
      case (cnt, cntMap) =>
        db.readOnlyReplica { implicit s =>
          LibraryCountData(cnt, cntMap)
        }
    }
  }

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since)
    }
    newConnections.toSeq
  }

  def usersToSendEmailTo(): Future[Seq[Id[User]]] = {
    val userIds: Seq[Id[User]] = db.readOnlyReplica { implicit session => userRepo.getAllIds() }.toSeq

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
        Tuple2(id, x)
      }
    }

    val fIdAndViewByLib: Future[Seq[(Id[User], LibraryCountData)]] = Future.sequence(idAndViewByLib)

    val result = fIdAndViewByLib.map { seq: Seq[(Id[User], LibraryCountData)] =>
      seq.filter {
        case (id: Id[User], viewsByLib: LibraryCountData) =>
          val totalLibFollows = getLibraryFollowerCounts(id).totalCount
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

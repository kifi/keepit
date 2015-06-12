package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future

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
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since)
      val cnt = cntMap.foldLeft[Int](0)((acc, kv) => acc + kv._2) // get total "follows" over all libraries
      LibraryCountData(cnt, cntMap)
    }
  }

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since)
    }
    newConnections.toSeq
  }

  def getGratData(userId: Id[User]): Future[GratificationData] = heimdal.getGratData(userId)

  def getEligibleGratData: Future[Seq[GratificationData]] = {

    def batchGetGratData(userIds: Seq[Id[User]]): Future[Seq[GratificationData]] = {
      val BATCH_SIZE = 1000
      val batches = {
        for (batch <- userIds.grouped(BATCH_SIZE)) yield {
          if (EXPERIMENT_DEPLOY) {
            heimdal.getGratDatas(batch.filter { id => localUserExperimentCommander.userHasExperiment(id, ExperimentType.GRATIFICATION_EMAIL) })
          } else {
            heimdal.getEligibleGratData(batch)
          }
        }
      }.toSeq
      Future.sequence(batches).map { _.flatten }
    }

    val userIds: Seq[Id[User]] = db.readOnlyReplica { implicit session => userRepo.getAllIds() }.toSeq

    batchGetGratData(userIds)
  }
}

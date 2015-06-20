package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, EmailAddress }
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
    emailSenderProvider: EmailSenderProvider,
    heimdal: HeimdalServiceClient) extends Logging {

  private val NUM_WEEKS_BACK = 1
  val EXPERIMENT_DEPLOY = false // only send emails to users with the GRATIFICATION_EMAIL experiment
  val MIN_FOLLOWERS = 1
  val MIN_VIEWS = 5
  val MIN_CONNECTIONS = 1

  private val remoteCallQueue = new ReactiveLock(numConcurrent = 5)

  private val BATCH_SIZE = 1000

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

  def generateUserBatch(batchNum: Int): Seq[Id[User]] = {
    db.readOnlyReplica { implicit s => userRepo.pageIncluding(UserStates.ACTIVE)(batchNum, BATCH_SIZE) }.map { user => user.id.get }
  }

  def getEligibleGratDatas(userIds: Seq[Id[User]]): Future[Seq[GratificationData]] = {
    if (EXPERIMENT_DEPLOY) {
      val users = userIds.filter { id => localUserExperimentCommander.userHasExperiment(id, ExperimentType.GRATIFICATION_EMAIL) }
      remoteCallQueue.withLockFuture { heimdal.getGratDatas(users) }
    } else {
      remoteCallQueue.withLockFuture { heimdal.getEligibleGratData(userIds) }
    }
  }

  def batchSendEmails(filter: Id[User] => Boolean, sendTo: Option[EmailAddress] = None): Unit = {
    val userCount = db.readOnlyReplica { implicit s => userRepo.count }
    val numBatches = userCount / BATCH_SIZE
    (0 to numBatches).foreach { batchNum =>
      val userIds: Seq[Id[User]] = generateUserBatch(batchNum).filter(filter)
      val fGratData: Future[Seq[GratificationData]] = getEligibleGratDatas(userIds)
      fGratData.foreach { log.info(s"Grat Data batch retrieval succeeded: batchNum=$batchNum, sending emails"); emailSenderProvider.gratification.sendToUsersWithData(_, sendTo) }
      fGratData.onFailure {
        case t: Throwable => log.error(s"Grat Data batch retrieval failed: batchNum=$batchNum")
      }
    }
  }
}

package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
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
  val EXPERIMENT_DEPLOY = true

  private val remoteCallQueue = new ReactiveLock(numConcurrent = 5)

  private val BATCH_SIZE = 1000

  def getLibraryFollowCounts(userId: Id[User]): CountData[Library] = {
    db.readOnlyReplica { implicit s =>
      val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
      // val cnt = libMemRepo.userRecentUniqueFollowerCounts(userId, since) // deprecated, since it gets unique followers. we currently use the total "follows" across libraries.
      val cntMap = libMemRepo.userRecentTopFollowedLibrariesAndCounts(userId, since)
      val cnt = cntMap.foldLeft[Int](0)((acc, kv) => acc + kv._2) // get total "follows" over all libraries
      CountData[Library](cnt, cntMap)
    }
  }

  def getNewConnections(userId: Id[User]): Seq[Id[User]] = {
    val since = currentDateTime.minusWeeks(NUM_WEEKS_BACK)
    val newConnections = db.readOnlyReplica { implicit s =>
      userConnectionRepo.getConnectionsSince(userId, since)
    }
    newConnections.toSeq
  }

  def getGratData(userId: Id[User]): Future[GratificationData] = heimdal.getGratData(userId).map { augmentData }

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
      val fGratData: Future[Seq[GratificationData]] = getEligibleGratDatas(userIds).map { _.map { augmentData }.filter { _.isEligible } }
      fGratData.foreach { log.info(s"Grat Data batch retrieval succeeded: batchNum=$batchNum, sending emails"); emailSenderProvider.gratification.sendToUsersWithData(_, sendTo) }
      fGratData.onFailure {
        case t: Throwable => log.error(s"Grat Data batch retrieval failed: batchNum=$batchNum")
      }
    }
  }

  private def augmentData(gratData: GratificationData): GratificationData = {
    val rawFollows = getLibraryFollowCounts(gratData.userId)
    gratData.copy(
      libraryViews = filterPrivateLibraries(gratData.libraryViews),
      libraryFollows = filterPrivateLibraries(rawFollows)
    )
  }

  private def filterPrivateLibraries(libCountData: CountData[Library]): CountData[Library] = {
    val publicLibViewsById = db.readOnlyReplica { implicit session =>
      libCountData.countById.filter {
        case (libId, _) =>
          libraryRepo.get(libId).visibility != LibraryVisibility.SECRET
      }
    }
    val publicLibViewsTotal = publicLibViewsById.values.sum
    CountData[Library](publicLibViewsTotal, publicLibViewsById)
  }
}

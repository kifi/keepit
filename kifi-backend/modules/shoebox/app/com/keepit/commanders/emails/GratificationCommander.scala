package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.{ FutureHelpers, ReactiveLock }
import com.keepit.common.db.{ Model, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ Repo, DbRepo, Database }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ ElectronicMail, EmailAddress }
import com.keepit.heimdal.HeimdalServiceClient
import com.keepit.model._
import com.keepit.common.time._
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.Future
import scala.util.{ Failure, Success }

@Singleton
class GratificationCommander @Inject() (
    db: Database,
    libMemRepo: LibraryMembershipRepo,
    libraryRepo: LibraryRepo,
    keepRepo: KeepRepo,
    userRepo: UserRepo,
    userExperimentRepo: UserExperimentRepo,
    localUserExperimentCommander: LocalUserExperimentCommander,
    emailSenderProvider: EmailSenderProvider,
    heimdal: HeimdalServiceClient) extends Logging {

  private val NUM_WEEKS_BACK = 1
  val UNDER_EXPERIMENT = false

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

  def getGratData(userId: Id[User]): Future[GratificationData] = heimdal.getGratData(userId).map { augmentData }

  def generateUserBatch(batchNum: Int): Seq[Id[User]] = {
    db.readOnlyReplica { implicit s => userRepo.pageAscendingIds(batchNum, BATCH_SIZE, excludeStates = Set(UserStates.INACTIVE)) }
  }

  def batchSendEmails(filter: Id[User] => Boolean, sendTo: Option[EmailAddress] = None): Unit = {
    log.info("[GratData] Starting grat data pipeline...")
    val userCount = db.readOnlyReplica { implicit s => userRepo.count }
    val numBatches = userCount / BATCH_SIZE

    def processBatch(dummyAcc: Unit, batch: Int): Future[Unit] = {
      val userIds = generateUserBatch(batch)
      log.info(s"[GratData] Generated user batch ${userIds.head}-${userIds.last}, getting data")
      val fGratDatas = heimdal.getEligibleGratDatas(userIds).map(_.map(augmentData))
      log.info(s"[GratData] Batched data received, sending emails")
      fGratDatas.map { gratDatas => emailSenderProvider.gratification.sendToUsersWithData(gratDatas); () }
    }

    if (!UNDER_EXPERIMENT) {
      FutureHelpers.foldLeft(0 to numBatches)(())(processBatch)
    } else {
      val userIds = db.readOnlyReplica { implicit session => userExperimentRepo.getUserIdsByExperiment(ExperimentType.GRATIFICATION_EMAIL) }
      val fGratDatas = heimdal.getEligibleGratDatas(userIds).map(_.map(augmentData))
      fGratDatas.map { gratDatas => emailSenderProvider.gratification.sendToUsersWithData(gratDatas); () }
    }

  }

  private def augmentData(gratData: GratificationData): GratificationData = {
    val rawFollows = getLibraryFollowCounts(gratData.userId)
    val libraryFilter = { library: Library => library.state != LibraryStates.INACTIVE && library.visibility != LibraryVisibility.SECRET }
    val keepFilter = { keep: Keep => keep.state != KeepStates.INACTIVE && keep.visibility != LibraryVisibility.SECRET }
    gratData.copy(
      libraryViews = applyCountDataFilter[Library](gratData.libraryViews, libraryRepo, libraryFilter),
      libraryFollows = applyCountDataFilter[Library](rawFollows, libraryRepo, libraryFilter),
      keepViews = applyCountDataFilter[Keep](gratData.keepViews, keepRepo, keepFilter),
      rekeeps = applyCountDataFilter[Keep](gratData.rekeeps, keepRepo, keepFilter)
    )
  }

  private def applyCountDataFilter[E <: Model[E]](countData: CountData[E], repo: Repo[E], filter: E => Boolean): CountData[E] = {
    val filteredCountById = db.readOnlyReplica { implicit session =>
      countData.countById.filter { case (id: Id[E], _) => filter(repo.get(id)) }
    }
    val publicCountTotal = filteredCountById.values.sum
    CountData[E](publicCountTotal, filteredCountById)
  }
}

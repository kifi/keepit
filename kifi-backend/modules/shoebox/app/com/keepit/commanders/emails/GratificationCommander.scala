package com.keepit.commanders.emails

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.LocalUserExperimentCommander
import com.keepit.common.concurrent.ReactiveLock
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

  def getGratData(userId: Id[User]): Future[GratificationData] = heimdal.getGratData(userId).map { augmentData }

  def generateUserBatch(batchNum: Int): Seq[Id[User]] = {
    db.readOnlyReplica { implicit s => userRepo.pageIncluding(UserStates.ACTIVE)(batchNum, BATCH_SIZE) }.map { user => user.id.get }
  }

  def getEligibleGratDatas(userIds: Seq[Id[User]]): Future[Seq[GratificationData]] = {
    if (EXPERIMENT_DEPLOY) {
      val users = userIds.filter { id => localUserExperimentCommander.userHasExperiment(id, ExperimentType.GRATIFICATION_EMAIL) }
      remoteCallQueue.withLockFuture { heimdal.getGratDatas(users) }
    } else {
      remoteCallQueue.withLockFuture { heimdal.getEligibleGratDatas(userIds) }
    }
  }

  def batchSendEmails(filter: Id[User] => Boolean, sendTo: Option[EmailAddress] = None): Unit = {
    val userCount = db.readOnlyReplica { implicit s => userRepo.count }
    val numBatches = userCount / BATCH_SIZE
    (0 to numBatches).foreach { batchNum =>
      generateUserBatch(batchNum).filter(filter) match {
        case userIds: Seq[Id[User]] if userIds.isEmpty =>
        case userIds: Seq[Id[User]] => {
          log.info(s"[GratData] userIds ${userIds.head}-${userIds.last} generated, getting data from heimdal")
          val fGratData: Future[Seq[GratificationData]] = getEligibleGratDatas(userIds).map {
            _.map {
              augmentData
            }.filter {
              _.isEligible
            }
          }
          fGratData.onComplete {
            case Success(gratDatas) =>
              log.info(s"Grat Data batch retrieval succeeded: batchNum=$batchNum, sending emails")
              emailSenderProvider.gratification.sendToUsersWithData(gratDatas, sendTo)
            case Failure(t) => log.error(s"Grat Data batch retrieval failed for batchNum $batchNum. Exception: ${t.getMessage}", t)
          }
        }
        case _ => log.warn("[GratData] odd behavior from generateUserBatch, didn't receive a seq of userIds")
      }
    }
  }

  private def augmentData(gratData: GratificationData): GratificationData = {
    val rawFollows = getLibraryFollowCounts(gratData.userId)
    val libraryFilter = { library: Library => library.state != LibraryStates.INACTIVE && library.visibility != LibraryVisibility.SECRET }
    val keepFilter = { keep: Keep => keep.state != KeepStates.INACTIVE && keep.visibility != LibraryVisibility.SECRET }
    gratData.copy(
      libraryViews = filterEntities[Library](gratData.libraryViews, libraryRepo, libraryFilter),
      libraryFollows = filterEntities[Library](rawFollows, libraryRepo, libraryFilter),
      keepViews = filterEntities[Keep](gratData.keepViews, keepRepo, keepFilter),
      rekeeps = filterEntities[Keep](gratData.rekeeps, keepRepo, keepFilter)
    )
  }

  private def filterEntities[E <: Model[E]](countData: CountData[E], repo: Repo[E], entityFilter: E => Boolean): CountData[E] = {
    val filteredCountById = db.readOnlyReplica { implicit session =>
      countData.countById.filter { case (id: Id[E], _) => entityFilter(repo.get(id)) }
    }
    val publicCountTotal = filteredCountById.values.sum
    CountData[E](publicCountTotal, filteredCountById)
  }
}

package com.keepit.cortex.models.lda

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.cortex.core.{ ModelVersion, StatModelName, FeatureRepresentation }
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.plugins.{ BaseFeatureUpdatePlugin, FeatureUpdatePlugin, FeatureUpdateActor, BaseFeatureUpdater, FeaturePluginMessages }
import com.keepit.model.User
import com.keepit.shoebox.ShoeboxServiceClient
import math.abs
import com.keepit.cortex.utils.MatrixUtils._
import scala.concurrent.Await
import scala.concurrent.duration._

case class LDAUserStatUpdate(userId: Id[User])

class LDAUserStatDbUpdateActor @Inject() (airbrake: AirbrakeNotifier, updater: LDAUserStatDbUpdater) extends FeatureUpdateActor(airbrake, updater) {
  import FeaturePluginMessages._

  override def receive() = {
    case Update => updater.update()
    case LDAUserStatUpdate(userId) => updater.updateUser(userId)
  }
}

trait LDAUserStatDbUpdatePlugin extends FeatureUpdatePlugin[User, DenseLDA] {
  def updateUser(userId: Id[User]): Unit
}

@Singleton
class LDAUserStatDbUpdatePluginImpl @Inject() (
    actor: ActorInstance[LDAUserStatDbUpdateActor],
    discovery: ServiceDiscovery,
    val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with LDAUserStatDbUpdatePlugin {

  def updateUser(userId: Id[User]): Unit = {
    actor.ref ! LDAUserStatUpdate(userId)
  }
}

@ImplementedBy(classOf[LDAUserStatDbUpdaterImpl])
trait LDAUserStatDbUpdater extends BaseFeatureUpdater[Id[User], User, DenseLDA, FeatureRepresentation[User, DenseLDA]] {
  def updateUser(userId: Id[User]): Unit
}

@Singleton
class LDAUserStatDbUpdaterImpl @Inject() (
    representer: MultiVersionedLDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    uriTopicRepo: URILDATopicRepo,
    userLDAStatsRepo: UserLDAStatsRepo,
    commitRepo: FeatureCommitInfoRepo,
    shoebox: ShoeboxServiceClient) extends LDAUserStatDbUpdater with Logging {

  private val fetchSize = 10000
  private val modelName = StatModelName.LDA_USER_STATS
  private val min_num_words = 50
  protected val min_num_evidence = 5

  type Auxiliary = (Option[LDATopic], Option[LDATopic], Option[LDATopic], Option[Float]) // 1st, 2nd, 3rd topic; 1st topic score

  def update(): Unit = {
    representer.versions.foreach { implicit version =>
      val tasks = fetchTasks
      log.info(s"fetched ${tasks.size} tasks")
      processTasks(tasks)
    }
  }

  def updateUser(userId: Id[User]): Unit = {
    representer.versions.foreach { implicit version =>
      processUser(userId)
    }
  }

  private def fetchTasks(implicit version: ModelVersion[DenseLDA]): Seq[CortexKeep] = {
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(modelName, version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = modelName, modelVersion = version.version, seq = 0L)) }

    val fromSeq = SequenceNumber[CortexKeep](commitOpt.map { _.seq }.getOrElse(0L))
    log.info(s"fetch tasks from ${fromSeq.value}")
    db.readOnlyReplica { implicit s => keepRepo.getSince(fromSeq, fetchSize) }
  }

  private def processTasks(keeps: Seq[CortexKeep])(implicit version: ModelVersion[DenseLDA]): Unit = {
    val users = keeps.map { _.userId }.distinct
    users.foreach { processUser(_) }
    log.info(s"${users.size} users processed")
    keeps.lastOption.map { keep =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, version.version).get
        log.info(s"committing with seq = ${keep.seq.value}")
        commitRepo.save(commitInfo.withSeq(keep.seq.value).withUpdateTime(currentDateTime))
      }
    }
  }

  private def processUser(userId: Id[User])(implicit version: ModelVersion[DenseLDA]): Unit = {
    val keeperOnly = userHasActivePersona(userId)
    val model = db.readOnlyReplica { implicit s => userLDAStatsRepo.getByUser(userId, version) }
    val numFeat = db.readOnlyReplica { implicit s => uriTopicRepo.countUserURIFeatures(userId, version, min_num_words, keeperOnly) }
    if (shouldComputeFeature(model, numFeat)) {
      val feats = db.readOnlyReplica { implicit s => uriTopicRepo.getUserURIFeatures(userId, version, min_num_words, keeperOnly) }
      val (mean, variance) = genMeanAndVar(feats)
      val (firstOpt, secondOpt, thirdOpt, firstScoreOpt) = getAuxiliary(mean)
      val state = if (feats.size >= min_num_evidence) UserLDAStatsStates.ACTIVE else UserLDAStatsStates.NOT_APPLICABLE
      val toSave = model match {
        case Some(m) => m.copy(numOfEvidence = feats.size, userTopicMean = mean, userTopicVar = variance, firstTopic = firstOpt, secondTopic = secondOpt, thirdTopic = thirdOpt, firstTopicScore = firstScoreOpt).withUpdateTime(currentDateTime).withState(state)
        case None => UserLDAStats(userId = userId, version = version, numOfEvidence = feats.size, firstTopic = firstOpt, secondTopic = secondOpt, thirdTopic = thirdOpt, firstTopicScore = firstScoreOpt, userTopicMean = mean, userTopicVar = variance, state = state)
      }
      db.readWrite { implicit s => userLDAStatsRepo.save(toSave) }
    }
  }

  private def shouldComputeFeature(model: Option[UserLDAStats], numOfEvidenceNow: Int): Boolean = {
    def changedMuch(numOfEvidenceBefore: Int, numOfEvidenceNow: Int) = {
      val diff = abs(numOfEvidenceNow - numOfEvidenceBefore)
      if (model.get.state != UserLDAStatsStates.ACTIVE && numOfEvidenceNow >= min_num_evidence) true
      else if (numOfEvidenceNow < min_num_evidence && model.get.state == UserLDAInterestsStates.ACTIVE) true
      else (diff.toFloat / numOfEvidenceBefore > 0.1f || diff > 100)
    }

    model.isEmpty || changedMuch(model.get.numOfEvidence, numOfEvidenceNow) || model.get.updatedAt.plusMonths(1).getMillis < currentDateTime.getMillis
  }

  private def genMeanAndVar(feats: Seq[LDATopicFeature]): (Option[UserTopicMean], Option[UserTopicVar]) = {
    if (feats.size < min_num_evidence) {
      (None, None)
    } else {
      val vecs = feats.map { x => x.value }
      val (mean, std) = getMeanAndStd(vecs)
      val variance = std.map { x => x * x }
      (Some(UserTopicMean(mean)), Some(UserTopicVar(variance)))
    }
  }

  private def getAuxiliary(userTopic: Option[UserTopicMean]): Auxiliary = {
    userTopic match {
      case Some(mean) =>
        val (first, second, third) = argmax3(mean.mean)
        val firstTopicScore = mean.mean(first)
        (Some(LDATopic(first)), Some(LDATopic(second)), Some(LDATopic(third)), Some(firstTopicScore))
      case None => (None, None, None, None)
    }
  }

  private def userHasActivePersona(userId: Id[User]): Boolean = {
    val ps = Await.result(shoebox.getUserActivePersonas(userId), 5 second)
    ps.personas.nonEmpty
  }
}


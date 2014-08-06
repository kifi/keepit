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
import com.keepit.cortex.core.{ StatModelName, FeatureRepresentation }
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.plugins.{ BaseFeatureUpdatePlugin, FeatureUpdatePlugin, FeatureUpdateActor, BaseFeatureUpdater }
import com.keepit.model.User
import math.abs
import com.keepit.cortex.utils.MatrixUtils._
import scala.concurrent.duration._

class LDAUserStatDbUpdateActor @Inject() (airbrake: AirbrakeNotifier, updater: LDAUserStatDbUpdater) extends FeatureUpdateActor(airbrake, updater)

trait LDAUserStatDbUpdatePlugin extends FeatureUpdatePlugin[User, DenseLDA]

@Singleton
class LDAUserStatDbUpdatePluginImpl @Inject() (
    actor: ActorInstance[LDAUserStatDbUpdateActor],
    discovery: ServiceDiscovery,
    val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with LDAUserStatDbUpdatePlugin {
  override val updateFrequency: FiniteDuration = 5 minutes
}

@ImplementedBy(classOf[LDAUserStatDbUpdaterImpl])
trait LDAUserStatDbUpdater extends BaseFeatureUpdater[Id[User], User, DenseLDA, FeatureRepresentation[User, DenseLDA]]

@Singleton
class LDAUserStatDbUpdaterImpl @Inject() (
    representer: LDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    uriTopicRepo: URILDATopicRepo,
    userLDAStatsRepo: UserLDAStatsRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDAUserStatDbUpdater with Logging {

  private val fetchSize = 5000
  private val modelName = StatModelName.LDA_USER_STATS
  private val min_num_words = 50
  protected val min_num_evidence = 50

  def update(): Unit = {
    val tasks = fetchTasks
    log.info(s"fetched ${tasks.size} tasks")
    processTasks(tasks)
  }

  private def fetchTasks(): Seq[CortexKeep] = {
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(modelName, representer.version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = modelName, modelVersion = representer.version.version, seq = 0L)) }

    val fromSeq = SequenceNumber[CortexKeep](commitOpt.map { _.seq }.getOrElse(0L))
    log.info(s"fetch tasks from ${fromSeq.value}")
    db.readOnlyReplica { implicit s => keepRepo.getSince(fromSeq, fetchSize) }
  }

  private def processTasks(keeps: Seq[CortexKeep]): Unit = {
    val users = keeps.map { _.userId }.distinct
    users.foreach { processUser(_) }
    log.info(s"${users.size} users processed")
    keeps.lastOption.map { keep =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, representer.version.version).get
        log.info(s"committing with seq = ${keep.seq.value}")
        commitRepo.save(commitInfo.withSeq(keep.seq.value).withUpdateTime(currentDateTime))
      }
    }
  }

  private def processUser(userId: Id[User]): Unit = {
    log.info(s"processing user ${userId}")
    val model = db.readOnlyReplica { implicit s => userLDAStatsRepo.getByUser(userId, representer.version) }
    val numFeat = db.readOnlyReplica { implicit s => uriTopicRepo.countUserURIFeatures(userId, representer.version, min_num_words) }
    if (shouldComputeFeature(model, numFeat)) {
      val feats = db.readOnlyReplica { implicit s => uriTopicRepo.getUserURIFeatures(userId, representer.version, min_num_words) }
      val (mean, variance) = genMeanAndVar(feats)
      val state = if (feats.size > min_num_evidence) UserLDAStatsStates.ACTIVE else UserLDAStatsStates.NOT_APPLICABLE
      val toSave = model match {
        case Some(m) => m.copy(numOfEvidence = feats.size, userTopicMean = mean, userTopicVar = variance).withUpdateTime(currentDateTime).withState(state)
        case None => UserLDAStats(userId = userId, version = representer.version, numOfEvidence = feats.size, userTopicMean = mean, userTopicVar = variance, state = state)
      }
      db.readWrite { implicit s => userLDAStatsRepo.save(toSave) }
    }
  }

  private def shouldComputeFeature(model: Option[UserLDAStats], numOfEvidenceNow: Int): Boolean = {
    def changedMuch(numOfEvidenceBefore: Int, numOfEvidenceNow: Int) = {
      val diff = abs(numOfEvidenceNow - numOfEvidenceBefore)
      if (numOfEvidenceBefore == 0) true
      else (diff.toFloat / numOfEvidenceBefore > 0.1f || diff > 100)
    }

    model.isEmpty || changedMuch(model.get.numOfEvidence, numOfEvidenceNow) || model.get.updatedAt.plusMonths(1).getMillis < currentDateTime.getMillis
  }

  private def genMeanAndVar(feats: Seq[LDATopicFeature]): (Option[UserTopicMean], Option[UserTopicVar]) = {
    if (feats.size < min_num_evidence) {
      (None, None)
    } else {
      val vecs = feats.map { x => toDoubleArray(x.value) }
      val (mean, std) = getMeanAndStd(vecs)
      val variance = std.map { x => x * x }
      (Some(UserTopicMean(mean)), Some(UserTopicVar(variance)))
    }
  }
}


package com.keepit.cortex.models.lda

import com.google.inject.{ ImplementedBy, Inject, Singleton }
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

import scala.concurrent.duration._

class LDAUserDbUpdateActor @Inject() (airbrake: AirbrakeNotifier, updater: LDAUserDbUpdater) extends FeatureUpdateActor(airbrake, updater)

trait LDAUserDbUpdatePlugin extends FeatureUpdatePlugin[User, DenseLDA]

@Singleton
class LDAUserDbUpdatePluginImpl @Inject() (
    actor: ActorInstance[LDAUserDbUpdateActor],
    discovery: ServiceDiscovery,
    val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with LDAUserDbUpdatePlugin {
  override val updateFrequency: FiniteDuration = 2 minutes
}

@ImplementedBy(classOf[LDAUserDbUpdaterImpl])
trait LDAUserDbUpdater extends BaseFeatureUpdater[Id[User], User, DenseLDA, FeatureRepresentation[User, DenseLDA]]

@Singleton
class LDAUserDbUpdaterImpl @Inject() (
    representer: LDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    uriTopicRepo: URILDATopicRepo,
    userTopicRepo: UserLDAInterestsRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDAUserDbUpdater with Logging {

  private val fetchSize = 5000
  private val modelName = StatModelName.LDA_USER

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

  private def processUser(user: Id[User]): Unit = {
    val model = db.readOnlyReplica { implicit s => userTopicRepo.getByUser(user, representer.version) }
    if (shouldComputeFeature(model)) {
      val topicCounts = db.readOnlyReplica { implicit s => uriTopicRepo.getUserTopicHistograms(user, representer.version) }
      val numOfEvidence = topicCounts.map { _._2 }.sum
      val topicMean = genFeature(topicCounts)
      val state = if (topicMean.isDefined) UserLDAInterestsStates.ACTIVE else UserLDAInterestsStates.NOT_APPLICABLE
      val tosave = model match {
        case Some(m) => m.copy(userTopicMean = topicMean).withUpdateTime(currentDateTime).withState(state)
        case None => UserLDAInterests(userId = user, version = representer.version, numOfEvidence = numOfEvidence, userTopicMean = topicMean, state = state)
      }
      db.readWrite { implicit s => userTopicRepo.save(tosave) }
    }
  }

  private def shouldComputeFeature(model: Option[UserLDAInterests]): Boolean = {
    model.isEmpty || model.get.updatedAt.plusDays(1).getMillis < currentDateTime.getMillis
  }

  private def genFeature(topicCounts: Seq[(LDATopic, Int)]): Option[UserTopicMean] = {
    if (topicCounts.isEmpty) return None
    val arr = new Array[Float](representer.dimension)
    topicCounts.foreach { case (topic, cnt) => arr(topic.index) += cnt }
    val s = arr.sum
    Some(UserTopicMean(arr.map { x => x / s }))
  }

}

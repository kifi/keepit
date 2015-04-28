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
import com.keepit.cortex.ModelVersions
import com.keepit.cortex.core.{ ModelVersion, StatModelName, FeatureRepresentation }
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.plugins.{ BaseFeatureUpdatePlugin, FeatureUpdatePlugin, FeatureUpdateActor, BaseFeatureUpdater }
import com.keepit.curator.CuratorServiceClient
import com.keepit.model.User
import com.keepit.cortex.utils.MatrixUtils.{ toDoubleArray, cosineDistance }
import org.joda.time.DateTime

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
    representer: MultiVersionedLDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    uriTopicRepo: URILDATopicRepo,
    userTopicRepo: UserLDAInterestsRepo,
    commitRepo: FeatureCommitInfoRepo,
    curator: CuratorServiceClient) extends LDAUserDbUpdater with Logging {

  private val fetchSize = 5000
  private val modelName = StatModelName.LDA_USER

  def update(): Unit = {
    representer.versions.foreach { implicit version =>
      val tasks = fetchTasks
      log.info(s"fetched ${tasks.size} tasks")
      processTasks(tasks)
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

  private def processUser(user: Id[User])(implicit version: ModelVersion[DenseLDA]): Unit = {
    val model = db.readOnlyReplica { implicit s => userTopicRepo.getByUser(user, version) }
    if (shouldComputeFeature(model, user)) {
      val topicCounts = db.readOnlyReplica { implicit s => uriTopicRepo.getUserTopicHistograms(user, version) }
      val numOfEvidence = topicCounts.map { _._2 }.sum
      val time = currentDateTime
      val recentTopicCounts = db.readOnlyReplica { implicit s =>
        uriTopicRepo.getSmartRecentUserTopicHistograms(user, version, noOlderThan = time.minusMonths(1), preferablyNewerThan = time.minusWeeks(1), minNum = 15, maxNum = 40)
      }
      val numOfRecentEvidence = recentTopicCounts.map { _._2 }.sum
      val topicMean = genFeature(topicCounts)
      val recentTopicMean = genFeature(recentTopicCounts)
      val state = if (topicMean.isDefined) UserLDAInterestsStates.ACTIVE else UserLDAInterestsStates.NOT_APPLICABLE
      val newModel = model match {
        case Some(m) => m.copy(numOfEvidence = numOfEvidence, userTopicMean = topicMean, numOfRecentEvidence = numOfRecentEvidence, userRecentTopicMean = recentTopicMean).withUpdateTime(currentDateTime).withState(state)
        case None => UserLDAInterests(userId = user, version = version, numOfEvidence = numOfEvidence, userTopicMean = topicMean, numOfRecentEvidence = numOfRecentEvidence, userRecentTopicMean = recentTopicMean,
          overallSnapshotAt = Some(time), overallSnapshot = topicMean, recencySnapshotAt = Some(time), recencySnapshot = recentTopicMean, state = state)
      }
      val (snaphShotChanged, tosave) = updateSnapshotIfNecessary(newModel)
      db.readWrite { implicit s => userTopicRepo.save(tosave) }
      if (snaphShotChanged && version == ModelVersions.defaultLDAVersion) { curator.refreshUserRecos(user) }
    }
  }

  private def shouldComputeFeature(model: Option[UserLDAInterests], userId: Id[User]): Boolean = {
    val window = 15

    def recentlyKeptMany(userId: Id[User]): Boolean = {
      val since = currentDateTime.minusMinutes(window)
      val cnt = db.readOnlyReplica { implicit s => keepRepo.countRecentUserKeeps(userId, since) }
      cnt > 10
    }

    def isOld(updatedAt: DateTime) = updatedAt.plusMinutes(window).getMillis < currentDateTime.getMillis

    model.isEmpty || isOld(model.get.updatedAt) || recentlyKeptMany(userId)
  }

  private def genFeature(topicCounts: Seq[(LDATopic, Int)])(implicit version: ModelVersion[DenseLDA]): Option[UserTopicMean] = {
    if (topicCounts.isEmpty) return None
    val arr = new Array[Float](representer.getDimension(version).get)
    topicCounts.foreach { case (topic, cnt) => arr(topic.index) += cnt }
    val s = arr.sum
    Some(UserTopicMean(arr.map { x => x / s }))
  }

  private def updateSnapshotIfNecessary(model: UserLDAInterests): (Boolean, UserLDAInterests) = {
    val m1Opt = model.userTopicMean
    val m2Opt = model.overallSnapshot
    val overallChanged = (m1Opt, m2Opt) match {
      case (Some(m1), Some(m2)) => if (cosineDistance(m1.mean, m2.mean) < 0.5f) true else false
      case (None, None) => false
      case _ => true
    }

    val recent1Opt = model.userRecentTopicMean
    val recent2Opt = model.recencySnapshot

    val recentChanged = (recent1Opt, recent2Opt) match {
      case (Some(m1), Some(m2)) => if (cosineDistance(m1.mean, m2.mean) < 0.2f) true else false // recent profile is more volatile
      case (None, None) => false
      case _ => true
    }

    (overallChanged, recentChanged) match {
      case (true, true) => (true, model.copy(overallSnapshot = model.userTopicMean, recencySnapshot = model.userRecentTopicMean, overallSnapshotAt = Some(currentDateTime), recencySnapshotAt = Some(currentDateTime)))
      case (true, false) => (true, model.copy(overallSnapshot = model.userTopicMean, overallSnapshotAt = Some(currentDateTime)))
      case (false, true) => (true, model.copy(recencySnapshot = model.userRecentTopicMean, recencySnapshotAt = Some(currentDateTime)))
      case (false, false) => (false, model)
    }
  }

}

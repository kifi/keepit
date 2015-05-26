package com.keepit.cortex.models.lda

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.SchedulingProperties
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.cortex.core.{ ModelVersion, FeatureRepresentation, StatModelName }
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.plugins._
import com.keepit.model.{ NormalizedURI, NormalizedURIStates, UrlHash }
import org.joda.time.DateTime

import scala.concurrent.duration._

trait UpdateAction
object UpdateAction {
  object CreateNewFeature extends UpdateAction
  object UpdateExistingFeature extends UpdateAction
  object DeactivateExistingFeature extends UpdateAction
  object Ignore extends UpdateAction
}

class LDADbUpdaterActor @Inject() (airbrake: AirbrakeNotifier, updater: LDADbUpdater) extends FeatureUpdateActor(airbrake, updater)

trait LDADbUpdatePlugin extends FeatureUpdatePlugin[NormalizedURI, DenseLDA]

@Singleton
class LDADbUpdatePluginImpl @Inject() (
    actor: ActorInstance[LDADbUpdaterActor],
    discovery: ServiceDiscovery,
    val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with LDADbUpdatePlugin {
  override val updateFrequency: FiniteDuration = 1 minutes
}

@ImplementedBy(classOf[LDADbUpdaterImpl])
trait LDADbUpdater extends BaseFeatureUpdater[Id[NormalizedURI], NormalizedURI, DenseLDA, FeatureRepresentation[NormalizedURI, DenseLDA]]

@Singleton
class LDADbUpdaterImpl @Inject() (
    representer: MultiVersionedLDAURIRepresenter,
    db: Database,
    uriRepo: CortexURIRepo,
    topicRepo: URILDATopicRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDADbUpdater with Logging {
  import com.keepit.cortex.models.lda.UpdateAction._

  private val fetchSize = 3000
  private val sparsity = 10

  assume(sparsity >= 3)

  private val modelName = StatModelName.LDA

  private implicit def toURISeq(seq: SequenceNumber[CortexURI]) = SequenceNumber[NormalizedURI](seq.value)

  def update(): Unit = {
    representer.versions.foreach { implicit version =>
      val tasks = fetchTasks
      log.info(s"fetched ${tasks.size} tasks")
      processTasks(tasks)
    }
  }

  private def fetchTasks(implicit version: ModelVersion[DenseLDA]): Seq[CortexURI] = {
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(modelName, version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = modelName, modelVersion = version.version, seq = 0L)) }

    val fromSeq = SequenceNumber[CortexURI](commitOpt.map { _.seq }.getOrElse(0L))
    log.info(s"fetch tasks from ${fromSeq.value}")
    db.readOnlyReplica { implicit s => uriRepo.getSince(fromSeq, fetchSize) }
  }

  private def processTasks(uris: Seq[CortexURI])(implicit version: ModelVersion[DenseLDA]): Unit = {
    uris.foreach { uri => processURI(uri) }

    log.info(s"${uris.size} uris processed")

    uris.lastOption.map { uri =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, version.version).get
        log.info(s"committing with seq = ${uri.seq.value}")
        commitRepo.save(commitInfo.withSeq(uri.seq.value).withUpdateTime(currentDateTime))
      }
    }
  }

  private def processURI(uri: CortexURI)(implicit version: ModelVersion[DenseLDA]): Unit = {
    updateAction(uri) match {
      case Ignore =>
      case CreateNewFeature => {
        val feat = computeFeature(uri)
        db.readWrite { implicit s => topicRepo.save(feat) }
      }

      case UpdateExistingFeature => {
        val curr = db.readOnlyReplica { implicit s => topicRepo.getByURI(uri.uriId, version) }.get
        val newFeat = computeFeature(uri)
        val delta = if (curr.firstTopic == newFeat.firstTopic) 0 else 1
        val updated = URILDATopic(
          id = curr.id,
          createdAt = curr.createdAt,
          updatedAt = currentDateTime,
          uriId = uri.uriId,
          uriSeq = uri.seq,
          version = version,
          numOfWords = newFeat.numOfWords,
          firstTopic = newFeat.firstTopic,
          secondTopic = newFeat.secondTopic,
          thirdTopic = newFeat.thirdTopic,
          firstTopicScore = newFeat.firstTopicScore,
          timesFirstTopicChanged = (curr.timesFirstTopicChanged + delta) min 65535,
          sparseFeature = newFeat.sparseFeature,
          feature = newFeat.feature,
          state = newFeat.state)

        db.readWrite { implicit s => topicRepo.save(updated) }
      }

      case DeactivateExistingFeature => {
        val curr = db.readOnlyReplica { implicit s => topicRepo.getByURI(uri.uriId, version) }.get
        val deactivated = curr.withUpdateTime(currentDateTime).withState(URILDATopicStates.INACTIVE).withSeq(uri.seq)
        db.readWrite { implicit s => topicRepo.save(deactivated) }
      }
    }
  }

  private def updateAction(uri: CortexURI)(implicit version: ModelVersion[DenseLDA]): UpdateAction = {
    import com.keepit.model.NormalizedURIStates._

    def isOneDayOld(time: DateTime) = time.plusDays(1).getMillis < currentDateTime.getMillis
    def couldHaveActiveLDAFeature(uri: CortexURI) = uri.shouldHaveContent && (uri.state.value != INACTIVE.value && uri.state.value != REDIRECTED.value)

    val infoOpt = db.readOnlyReplica { implicit s => topicRepo.getUpdateTimeAndState(uri.uriId, version) }

    (couldHaveActiveLDAFeature(uri), infoOpt) match {
      case (true, None) => CreateNewFeature
      case (true, Some((updatedAt, URILDATopicStates.NOT_APPLICABLE))) => UpdateExistingFeature
      case (true, Some((updatedAt, URILDATopicStates.INACTIVE))) => UpdateExistingFeature
      case (true, Some((updatedAt, URILDATopicStates.ACTIVE))) if (isOneDayOld(updatedAt)) => UpdateExistingFeature
      case (false, Some((_, URILDATopicStates.ACTIVE))) => DeactivateExistingFeature
      case _ => Ignore
    }
  }

  private def computeFeature(uri: CortexURI)(implicit version: ModelVersion[DenseLDA]): URILDATopic = {
    val normUri = NormalizedURI(id = Some(uri.uriId), seq = SequenceNumber[NormalizedURI](uri.seq.value), url = "", urlHash = UrlHash(""))
    representer.getRepresenter(version).get.genFeatureAndWordCount(normUri) match {
      case (None, cnt) => URILDATopic(uriId = uri.uriId, uriSeq = SequenceNumber[NormalizedURI](uri.seq.value), version = version, numOfWords = cnt, state = URILDATopicStates.NOT_APPLICABLE)
      case (Some(feat), cnt) => {
        val arr = feat.vectorize
        val sparse = arr.zipWithIndex.sortBy(-1f * _._1).take(sparsity).map { case (score, idx) => (LDATopic(idx), score) }
        val Array(first, second, third) = sparse.take(3).map { _._1 }
        URILDATopic(
          uriId = uri.uriId,
          uriSeq = uri.seq,
          version = version,
          numOfWords = cnt,
          firstTopic = Some(first),
          secondTopic = Some(second),
          thirdTopic = Some(third),
          firstTopicScore = Some(sparse.head._2),
          sparseFeature = Some(SparseTopicRepresentation(dimension = representer.getDimension(version).get, topics = sparse.toMap)),
          feature = Some(LDATopicFeature(arr)),
          state = URILDATopicStates.ACTIVE)
      }
    }
  }
}

class LDADbFeatureRetriever @Inject() (
    db: Database,
    topicRepo: URILDATopicRepo) {

  def getLDAFeaturesChanged(lowSeq: SequenceNumber[NormalizedURI], fetchSize: Int, version: ModelVersion[DenseLDA]): Seq[URILDATopic] = {
    db.readOnlyReplica { implicit s => topicRepo.getFeaturesSince(lowSeq, version, fetchSize) }
  }

}

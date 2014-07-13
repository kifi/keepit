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
import com.keepit.cortex.core.{ FeatureRepresentation, StatModelName }
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
  override val startTime: FiniteDuration = 60 seconds
  override val updateFrequency: FiniteDuration = 1 minutes
}

@ImplementedBy(classOf[LDADbUpdaterImpl])
trait LDADbUpdater extends BaseFeatureUpdater[Id[NormalizedURI], NormalizedURI, DenseLDA, FeatureRepresentation[NormalizedURI, DenseLDA]]

@Singleton
class LDADbUpdaterImpl @Inject() (
    representer: LDAURIRepresenter,
    db: Database,
    uriRepo: CortexURIRepo,
    topicRepo: URILDATopicRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDADbUpdater with Logging {
  import com.keepit.cortex.models.lda.UpdateAction._
  import com.keepit.model.NormalizedURIStates.SCRAPED

  private val fetchSize = 2000
  private val sparsity = 10

  assume(sparsity >= 3)

  private val modelName = StatModelName.LDA

  private implicit def toURISeq(seq: SequenceNumber[CortexURI]) = SequenceNumber[NormalizedURI](seq.value)

  def update(): Unit = {
    val tasks = fetchTasks
    log.info(s"fetched ${tasks.size} tasks")
    processTasks(tasks)
  }

  private def fetchTasks(): Seq[CortexURI] = {
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(modelName, representer.version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = modelName, modelVersion = representer.version.version, seq = 0L)) }

    val fromSeq = SequenceNumber[CortexURI](commitOpt.map { _.seq }.getOrElse(0L))
    log.info(s"fetch tasks from ${fromSeq.value}")
    db.readOnlyReplica { implicit s => uriRepo.getSince(fromSeq, fetchSize) }
  }

  private def processTasks(uris: Seq[CortexURI]): Unit = {
    uris.foreach { uri => processURI(uri) }

    log.info(s"${uris.size} uris processed")

    uris.lastOption.map { uri =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, representer.version.version).get
        log.info(s"committing with seq = ${uri.seq.value}")
        commitRepo.save(commitInfo.withSeq(uri.seq.value).withUpdateTime(currentDateTime))
      }
    }
  }

  private def processURI(uri: CortexURI): Unit = {
    updateAction(uri) match {
      case Ignore =>
      case CreateNewFeature => {
        val feat = computeFeature(uri)
        db.readWrite { implicit s => topicRepo.save(feat) }
      }

      case UpdateExistingFeature => {
        val curr = db.readOnlyReplica { implicit s => topicRepo.getByURI(uri.uriId, representer.version) }.get
        val newFeat = computeFeature(uri)
        val updated = URILDATopic(
          id = curr.id,
          createdAt = curr.createdAt,
          updatedAt = currentDateTime,
          uriId = uri.uriId,
          uriSeq = uri.seq,
          version = representer.version,
          firstTopic = newFeat.firstTopic,
          secondTopic = newFeat.secondTopic,
          thirdTopic = newFeat.thirdTopic,
          sparseFeature = newFeat.sparseFeature,
          feature = newFeat.feature,
          state = newFeat.state)

        db.readWrite { implicit s => topicRepo.save(updated) }
      }

      case DeactivateExistingFeature => {
        val curr = db.readOnlyReplica { implicit s => topicRepo.getByURI(uri.uriId, representer.version) }.get
        val deactivated = curr.withUpdateTime(currentDateTime).withState(URILDATopicStates.INACTIVE).withSeq(uri.seq)
        db.readWrite { implicit s => topicRepo.save(deactivated) }
      }
    }
  }

  private def updateAction(uri: CortexURI): UpdateAction = {
    def isTwoWeeksOld(time: DateTime) = time.plusWeeks(2).getMillis < currentDateTime.getMillis
    def isThreeDaysOld(time: DateTime) = time.plusDays(3).getMillis < currentDateTime.getMillis

    val infoOpt = db.readOnlyReplica { implicit s => topicRepo.getUpdateTimeAndState(uri.uriId, representer.version) }

    (uri.state.value, infoOpt) match {
      case (SCRAPED.value, None) => CreateNewFeature
      case (SCRAPED.value, Some((updatedAt, URILDATopicStates.NOT_APPLICABLE))) if (isTwoWeeksOld(updatedAt)) => UpdateExistingFeature
      case (SCRAPED.value, Some((updatedAt, URILDATopicStates.INACTIVE))) => UpdateExistingFeature
      case (SCRAPED.value, Some((updatedAt, URILDATopicStates.ACTIVE))) if (isThreeDaysOld(updatedAt)) => UpdateExistingFeature
      case (state, None) if (state != SCRAPED.value) => Ignore
      case (state, Some((_, URILDATopicStates.ACTIVE))) if (state != SCRAPED.value) => DeactivateExistingFeature
      case _ => Ignore
    }
  }

  private def computeFeature(uri: CortexURI): URILDATopic = {
    val normUri = NormalizedURI(id = Some(uri.uriId), seq = SequenceNumber[NormalizedURI](uri.seq.value), url = "", urlHash = UrlHash(""))
    representer(normUri) match {
      case None => URILDATopic(uriId = uri.uriId, uriSeq = SequenceNumber[NormalizedURI](uri.seq.value), version = representer.version, state = URILDATopicStates.NOT_APPLICABLE)
      case Some(feat) => {
        val arr = feat.vectorize
        val sparse = arr.zipWithIndex.sortBy(-1f * _._1).take(sparsity).map { case (score, idx) => (LDATopic(idx), score) }
        val Array(first, second, third) = sparse.take(3).map { _._1 }
        URILDATopic(
          uriId = uri.uriId,
          uriSeq = uri.seq,
          version = representer.version,
          firstTopic = Some(first),
          secondTopic = Some(second),
          thirdTopic = Some(third),
          sparseFeature = Some(SparseTopicRepresentation(dimension = representer.dimension, topics = sparse.toMap)),
          feature = Some(LDATopicFeature(arr)),
          state = URILDATopicStates.ACTIVE)
      }
    }
  }
}

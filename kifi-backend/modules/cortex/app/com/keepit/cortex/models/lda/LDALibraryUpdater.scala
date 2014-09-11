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
import com.keepit.model.{ LibraryStates, LibraryKind, Library }
import com.keepit.cortex.utils.MatrixUtils._
import org.joda.time.DateTime

@Singleton
class LDALibraryUpdaterImpl @Inject() (
    representer: LDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    libRepo: CortexLibraryRepo,
    libraryLDARepo: LibraryLDATopicRepo,
    uriTopicRepo: URILDATopicRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDALibraryUpdater with Logging {

  private val fetchSize = 10000
  private val modelName = StatModelName.LDA_LIBRARY
  private val min_num_words = 50
  protected val min_num_evidence = 1

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

  private def processTasks(keeps: Seq[CortexKeep]) = {
    val libs = keeps.flatMap { _.libraryId }.distinct
    libs.foreach { processLibrary(_) }
    log.info(s"${libs.size} libs processed")
    keeps.lastOption.map { keep =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, representer.version.version).get
        log.info(s"committing with keep seq = ${keep.seq.value}")
        commitRepo.save(commitInfo.withSeq(keep.seq.value))
      }
    }

  }

  private def processLibrary(libId: Id[Library]): Unit = {
    val model = db.readOnlyReplica { implicit s => libraryLDARepo.getByLibraryId(libId, representer.version) }
    if (shouldComputeFeature(libId, model)) {
      val feats = db.readOnlyReplica { implicit s => uriTopicRepo.getLibraryURIFeatures(libId, representer.version, min_num_words) }
      val mean = getLibraryTopicMean(feats)
      val state = if (mean.isDefined) LibraryLDATopicStates.ACTIVE else LibraryLDATopicStates.NOT_APPLICABLE
      val toSave = model match {
        case Some(m) => m.copy(numOfEvidence = feats.size, topic = mean, state = state)
        case None => LibraryLDATopic(libraryId = libId, version = representer.version, numOfEvidence = feats.size, topic = mean, state = state)
      }
      db.readWrite { implicit s => libraryLDARepo.save(toSave) }
    }
  }

  private def shouldComputeFeature(libId: Id[Library], model: Option[LibraryLDATopic]): Boolean = {
    def isApplicableLibrary(lib: Option[CortexLibrary]): Boolean = lib.exists(x => x.state.value == LibraryStates.ACTIVE.value && x.kind != LibraryKind.SYSTEM_MAIN && x.kind != LibraryKind.SYSTEM_SECRET)
    def isOld(updatedAt: DateTime) = updatedAt.plusMinutes(5).getMillis < currentDateTime.getMillis

    val lib = db.readOnlyReplica { implicit s => libRepo.getByLibraryId(libId) }
    isApplicableLibrary(lib) && (model.isEmpty || isOld(model.get.updatedAt))
  }

  private def getLibraryTopicMean(feats: Seq[LDATopicFeature]): Option[LibraryTopicMean] = {
    if (feats.size < min_num_evidence) None
    else {
      val vecs = feats.map { x => toDoubleArray(x.value) }
      val mean = average(vecs)
      Some(LibraryTopicMean(mean))
    }
  }
}

class LDALibraryUpdaterActor @Inject() (airbrake: AirbrakeNotifier, updater: LDALibraryUpdater) extends FeatureUpdateActor(airbrake, updater)

trait LDALibraryUpdaterPlugin extends FeatureUpdatePlugin[Library, DenseLDA]

@Singleton
class LDALibraryUpdaterPluginImpl @Inject() (
  actor: ActorInstance[LDALibraryUpdaterActor],
  discovery: ServiceDiscovery,
  val scheduling: SchedulingProperties) extends BaseFeatureUpdatePlugin(actor, discovery) with LDALibraryUpdaterPlugin

@ImplementedBy(classOf[LDALibraryUpdaterImpl])
trait LDALibraryUpdater extends BaseFeatureUpdater[Id[Library], Library, DenseLDA, FeatureRepresentation[Library, DenseLDA]]

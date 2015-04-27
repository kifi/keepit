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
import com.keepit.cortex.core.{ ModelVersion, StatModelName, FeatureRepresentation }
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.plugins.{ BaseFeatureUpdatePlugin, FeatureUpdatePlugin, FeatureUpdateActor, BaseFeatureUpdater }
import com.keepit.model.{ LibraryStates, LibraryKind, Library }
import com.keepit.cortex.utils.MatrixUtils._
import org.joda.time.DateTime

@Singleton
class LDALibraryUpdaterImpl @Inject() (
    representer: MultiVersionedLDAURIRepresenter,
    db: Database,
    keepRepo: CortexKeepRepo,
    libRepo: CortexLibraryRepo,
    libraryLDARepo: LibraryLDATopicRepo,
    uriTopicRepo: URILDATopicRepo,
    commitRepo: FeatureCommitInfoRepo) extends LDALibraryUpdater with Logging {

  private val fetchSize = 10000
  private val modelName = StatModelName.LDA_LIBRARY
  private val cleanupSeq = StatModelName.LDA_LIBRARY_CLEANUP
  private val cleanupBatch = 2000
  private val min_num_words = 50
  protected val min_num_evidence = 2

  type Auxiliary = (Option[LDATopic], Option[LDATopic], Option[LDATopic], Option[Float], Option[Float]) // (1st topic, 2nd topic, 3rd topic, 1st topic score, entropy)

  private def acceptLibraryKind(x: LibraryKind): Boolean = (x == LibraryKind.USER_CREATED)

  def update(): Unit = {
    representer.versions.foreach { implicit version =>
      val tasks = fetchTasks
      log.info(s"fetched ${tasks.size} tasks")
      processTasks(tasks)
    }

    representer.versions.foreach { version => cleanup(version) }
  }

  /**
   * main update() logic is driven by keep's seqNum. This cleanup process is driven by Library's seqNum. This is mostly designed for:
   * library changes state from active to inactive or vice versa. Such event is not caputured from keep events, we want to
   * make sure inactive library has inactive library LDA feature.
   * NOTE: In update(), we never bring an inactive library's feature from inactive to active, and for an active library, we never bring
   * its feature state from active to inactive. In cleanup(), the only mutation is switching active and inactive state of library features.
   * Thus, update() and cleanup() perform "disjoint" mutations. This is critical, because we are listening on two seqNums, and the two seqNum
   * may proceed at different speed. We don't want mutation from one method is overwritten by the other method, in an unexpected way.
   */
  private def cleanup(version: ModelVersion[DenseLDA]): Unit = {

    // fetch libs from seq
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(cleanupSeq, version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = cleanupSeq, modelVersion = version.version, seq = 0L)) }
    val fromSeq = SequenceNumber[CortexLibrary](commitOpt.map { _.seq }.getOrElse(0L))
    val libs = db.readOnlyReplica { implicit s => libRepo.getSince(fromSeq, cleanupBatch) }

    log.info(s"library lda updater cleanup, version = ${version}, fromSeq = ${fromSeq}, num of libs = ${libs.size}")

    // make sure non-system library has consitent library state and library feature state
    libs.filter { x => acceptLibraryKind(x.kind) }
      .foreach { lib =>
        db.readWrite { implicit s =>
          val libFeatOpt = libraryLDARepo.getByLibraryId(lib.libraryId, version)

          libFeatOpt.foreach { libFeat =>
            (lib.state.value, libFeat.state.value) match {
              case ("inactive", "active") => libraryLDARepo.save(libFeat.copy(state = LibraryLDATopicStates.INACTIVE))
              case ("active", "inactive") => libraryLDARepo.save(libFeat.copy(state = LibraryLDATopicStates.ACTIVE))
              case _ =>
            }
          }
        }
      }

    // commit seq
    libs.lastOption.foreach { lib =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(cleanupSeq, version.version).get
        log.info(s"one round of cleanup is done. Committing with lib seq = ${lib.seq.value}")
        commitRepo.save(commitInfo.withSeq(lib.seq.value))
      }
    }
  }

  private def fetchTasks(implicit version: ModelVersion[DenseLDA]): Seq[CortexKeep] = {
    val commitOpt = db.readOnlyReplica { implicit s => commitRepo.getByModelAndVersion(modelName, version.version) }
    if (commitOpt.isEmpty) db.readWrite { implicit s => commitRepo.save(FeatureCommitInfo(modelName = modelName, modelVersion = version.version, seq = 0L)) }

    val fromSeq = SequenceNumber[CortexKeep](commitOpt.map { _.seq }.getOrElse(0L))
    log.info(s"fetch tasks from ${fromSeq.value}")
    db.readOnlyReplica { implicit s => keepRepo.getSince(fromSeq, fetchSize) }
  }

  private def processTasks(keeps: Seq[CortexKeep])(implicit version: ModelVersion[DenseLDA]) = {
    val libs = keeps.flatMap { _.libraryId }.distinct
    libs.foreach { processLibrary(_) }
    log.info(s"${libs.size} libs processed")
    keeps.lastOption.map { keep =>
      db.readWrite { implicit s =>
        val commitInfo = commitRepo.getByModelAndVersion(modelName, version.version).get
        log.info(s"committing with keep seq = ${keep.seq.value}")
        commitRepo.save(commitInfo.withSeq(keep.seq.value))
      }
    }

  }

  private def processLibrary(libId: Id[Library])(implicit version: ModelVersion[DenseLDA]): Unit = {
    val model = db.readOnlyReplica { implicit s => libraryLDARepo.getByLibraryId(libId, version) }
    if (shouldComputeFeature(libId, model)) {
      val feats = db.readOnlyReplica { implicit s => uriTopicRepo.getLibraryURIFeatures(libId, version, min_num_words) }
      val mean = getLibraryTopicMean(feats)
      val (firstOpt, secondOpt, thirdOpt, firstScoreOpt, entropyOpt) = getAuxiliary(mean)
      val state = if (mean.isDefined) LibraryLDATopicStates.ACTIVE else LibraryLDATopicStates.NOT_APPLICABLE
      val toSave = model match {
        case Some(m) =>
          m.copy(numOfEvidence = feats.size, topic = mean, state = state, firstTopic = firstOpt, secondTopic = secondOpt, thirdTopic = thirdOpt, firstTopicScore = firstScoreOpt, entropy = entropyOpt)
        case None =>
          LibraryLDATopic(
            libraryId = libId,
            version = version,
            numOfEvidence = feats.size,
            topic = mean,
            state = state,
            firstTopic = firstOpt,
            secondTopic = secondOpt,
            thirdTopic = thirdOpt,
            firstTopicScore = firstScoreOpt,
            entropy = entropyOpt)
      }
      db.readWrite { implicit s => libraryLDARepo.save(toSave) }
    }
  }

  private def shouldComputeFeature(libId: Id[Library], model: Option[LibraryLDATopic]): Boolean = {
    def isApplicableLibrary(lib: Option[CortexLibrary]): Boolean = lib.exists(x => x.state.value == LibraryStates.ACTIVE.value && acceptLibraryKind(x.kind))

    val lib = db.readOnlyReplica { implicit s => libRepo.getByLibraryId(libId) }
    isApplicableLibrary(lib)
  }

  private def getLibraryTopicMean(feats: Seq[LDATopicFeature]): Option[LibraryTopicMean] = {
    if (feats.size < min_num_evidence) None
    else {
      val vecs = feats.map { x => x.value }
      val mean = average(vecs)
      Some(LibraryTopicMean(mean))
    }
  }

  private def getAuxiliary(libTopic: Option[LibraryTopicMean]): Auxiliary = {
    libTopic match {
      case Some(mean) =>
        val (first, second, third) = argmax3(mean.value)
        val firstTopicScore = mean.value(first)
        val entro = entropy(mean.value).toFloat
        (Some(LDATopic(first)), Some(LDATopic(second)), Some(LDATopic(third)), Some(firstTopicScore), Some(entro))
      case None => (None, None, None, None, None)
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

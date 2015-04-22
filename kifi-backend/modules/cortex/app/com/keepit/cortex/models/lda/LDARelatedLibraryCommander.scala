package com.keepit.cortex.models.lda

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulingProperties, SchedulerPlugin }
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.cortex.ModelVersions._
import com.keepit.model.Library
import scala.concurrent.duration._
import com.keepit.common.time._

@ImplementedBy(classOf[LDARelatedLibraryCommanderImpl])
trait LDARelatedLibraryCommander {
  type AdjacencyList = Seq[(Id[Library], Float)]

  def update(version: ModelVersion[DenseLDA]): Unit
  def cleanFewKeepsLibraries(version: ModelVersion[DenseLDA], readOnly: Boolean): Unit
  protected def computeEdgeWeight(source: LibraryLDATopic, dest: LibraryLDATopic): Float
  protected def computeFullAdjacencyList(source: LibraryLDATopic, libs: Seq[LibraryLDATopic]): AdjacencyList
  protected def sparsify(adjacencyList: AdjacencyList, topK: Int, minWeight: Float): AdjacencyList
  protected def saveAdjacencyList(sourceId: Id[Library], neighbors: AdjacencyList, version: ModelVersion[DenseLDA]): Unit
}

@Singleton
class LDARelatedLibraryCommanderImpl @Inject() (
    db: Database,
    libTopicRepo: LibraryLDATopicRepo,
    relatedLibRepo: LDARelatedLibraryRepo) extends LDARelatedLibraryCommander with Logging {

  private val TOP_K = 50
  private val MIN_WEIGHT = 0.7f
  private val SOURCE_MIN_EVIDENCE = 2
  private val DEST_MIN_EVIDENCE = 5
  private var hasFullyUpdated = false

  def fullyUpdateMode: Boolean = !hasFullyUpdated

  def update(version: ModelVersion[DenseLDA]): Unit = {
    if (!hasFullyUpdated) {
      fullUpdate(version)
      hasFullyUpdated = true
    } else {
      partialUpdate(version)
    }
  }

  // completely reconstruct an asymetric graph
  def fullUpdate(version: ModelVersion[DenseLDA]): Unit = {
    log.info(s"update LDA related library graph for version ${version.version}")
    val sourceLibs = db.readOnlyReplica { implicit s => libTopicRepo.getAllActiveByVersion(version, SOURCE_MIN_EVIDENCE) }
    val destLibs = sourceLibs.filter(_.numOfEvidence >= DEST_MIN_EVIDENCE)
    sourceLibs.foreach { source => computeAndPersistEdges(source, destLibs)(version) }
    log.info(s"finished updating LDA related library graph for version ${version.version}")
  }

  private def computeAndPersistEdges(source: LibraryLDATopic, libTopics: Seq[LibraryLDATopic])(implicit version: ModelVersion[DenseLDA]): Unit = {
    val sourceId = source.libraryId
    val full = computeFullAdjacencyList(source, libTopics)
    val sparse = sparsify(full, TOP_K, MIN_WEIGHT)
    saveAdjacencyList(sourceId, sparse, version)
  }

  private def deactivateEdges(vertex: Id[Library], version: ModelVersion[DenseLDA]): Unit = {
    db.readWrite { implicit s =>
      val outgoing = relatedLibRepo.getRelatedLibraries(vertex, version, excludeState = Some(LDARelatedLibraryStates.INACTIVE))
      val incoming = relatedLibRepo.getIncomingEdges(vertex, version, excludeState = Some(LDARelatedLibraryStates.INACTIVE))
      outgoing.foreach { x => relatedLibRepo.save(x.copy(state = LDARelatedLibraryStates.INACTIVE)) }
      incoming.foreach { x => relatedLibRepo.save(x.copy(state = LDARelatedLibraryStates.INACTIVE)) }
    }
  }

  // creates new vertexes, delete inactive ones.
  def partialUpdate(version: ModelVersion[DenseLDA]): Unit = {
    log.info(s"begin partial update LDA related library graph for version ${version.version}")
    val now = currentDateTime
    val updates = db.readOnlyReplica { implicit s => libTopicRepo.getRecentUpdated(version, since = now.minusHours(3)) }
    val (active, others) = updates.partition(_.state == LibraryLDATopicStates.ACTIVE)
    val (newSources, _) = active.partition { x => db.readOnlyReplica { implicit s => relatedLibRepo.getNeighborIdsAndWeights(x.libraryId, version).isEmpty } } // query could be optimized

    if (newSources.size > 0) {
      val destLibs = db.readOnlyReplica { implicit s => libTopicRepo.getAllActiveByVersion(version, DEST_MIN_EVIDENCE) }
      newSources.foreach { source => computeAndPersistEdges(source, destLibs)(version) }
    }

    others.foreach { x => deactivateEdges(x.libraryId, version) }
    log.info(s"done with partial update LDA related library graph for version ${version.version}")
  }

  def cleanFewKeepsLibraries(version: ModelVersion[DenseLDA], readOnly: Boolean): Unit = {
    val thresh = 2
    val libs = db.readOnlyReplica { implicit s => libTopicRepo.all() }
      .filter(x => x.numOfEvidence < thresh && x.version == version && x.state == LDARelatedLibraryStates.ACTIVE)
      .map { _.libraryId } // admin operation. not optimized for performance.

    log.info(s"found ${libs.size} small libraries to clean, threshold is $thresh. A few examples: ${libs.take(10).mkString(", ")}")
    if (!readOnly) {
      log.info("relate library graph clean up in progress ...")
      libs.foreach { lib =>
        db.readWrite { implicit s =>
          val edges = relatedLibRepo.getRelatedLibraries(lib, version, excludeState = Some(LDARelatedLibraryStates.INACTIVE))
          edges.foreach { e => relatedLibRepo.save(e.copy(state = LDARelatedLibraryStates.INACTIVE)) }
        }
      }
      log.info("relate library graph clean up is done.")
    }
  }

  def computeEdgeWeight(source: LibraryLDATopic, dest: LibraryLDATopic): Float = {
    // no self loop
    if (source.libraryId == dest.libraryId) {
      0f
    } else {
      (source.topic, dest.topic) match {
        case (Some(v), Some(w)) => cosineDistance(v.value, w.value)
        case _ => 0f
      }
    }
  }

  def computeFullAdjacencyList(source: LibraryLDATopic, libs: Seq[LibraryLDATopic]): AdjacencyList = {
    libs.map { dest => (dest.libraryId, computeEdgeWeight(source, dest)) }
  }

  def sparsify(adjacencyList: AdjacencyList, topK: Int, minWeight: Float): AdjacencyList = {
    val kthLarge = adjacencyList.map { _._2 }.sortBy(x => -x).take(topK).last
    val thresh = kthLarge max minWeight
    adjacencyList.filter(_._2 >= thresh)
  }

  def saveAdjacencyList(sourceId: Id[Library], neighbors: AdjacencyList, version: ModelVersion[DenseLDA]): Unit = {
    val oldList = db.readOnlyReplica { implicit s => relatedLibRepo.getRelatedLibraries(sourceId, version, excludeState = None) }
    val destWeights = neighbors.toMap
    val newDestSet = destWeights.keySet
    val oldDestSet = oldList.map { _.destId }.toSet
    val (update, delete, create) = (oldDestSet & newDestSet, oldDestSet -- newDestSet, newDestSet -- oldDestSet)

    db.readWrite { implicit s =>

      oldList.foreach { edge =>
        if (update.contains(edge.destId)) {
          relatedLibRepo.save(edge.copy(weight = destWeights(edge.destId), state = LDARelatedLibraryStates.ACTIVE))
        }
        if (delete.contains(edge.destId)) {
          relatedLibRepo.save(edge.copy(state = LDARelatedLibraryStates.INACTIVE))
        }
      }

      create.foreach { destId =>
        relatedLibRepo.save(LDARelatedLibrary(version = version, sourceId = sourceId, destId = destId, weight = destWeights(destId)))
      }
    }

  }
}

trait LDARelatedLibraryMessage
case object UpdateLDARelatedLibrary extends LDARelatedLibraryMessage

class LDARelatedLibraryActor @Inject() (
    relatedLibCommander: LDARelatedLibraryCommander,
    airbrake: AirbrakeNotifier) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case UpdateLDARelatedLibrary =>
      availableLDAVersions.foreach { version => relatedLibCommander.update(version) }
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[LDARelatedLibraryPluginImpl])
trait LDARelatedLibraryPlugin extends SchedulerPlugin

@Singleton
class LDARelatedLibraryPluginImpl @Inject() (
    actor: ActorInstance[LDARelatedLibraryActor],
    val scheduling: SchedulingProperties) extends LDARelatedLibraryPlugin {

  override def enabled = true

  override def onStart() {
    scheduleTaskOnOneMachine(actor.system, 10 minutes, 3 hours, actor.ref, UpdateLDARelatedLibrary, UpdateLDARelatedLibrary.getClass.getSimpleName)
  }
}

package com.keepit.cortex.models.lda

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel._
import com.keepit.cortex.utils.MatrixUtils._
import com.keepit.model.Library

@ImplementedBy(classOf[LDARelatedLibraryUpdaterImpl])
trait LDARelatedLibraryUpdater {
  type AdjacencyList = Seq[(Id[Library], Float)]

  def update(version: ModelVersion[DenseLDA]): Unit
  protected def computeEdgeWeight(source: LibraryLDATopic, dest: LibraryLDATopic): Float
  protected def computeFullAdjacencyList(source: LibraryLDATopic, libs: Seq[LibraryLDATopic]): AdjacencyList
  protected def sparsify(adjacencyLists: AdjacencyList, topK: Int, minWeight: Float): AdjacencyList
  protected def saveAdjacencyList(sourceId: Id[Library], neighbors: AdjacencyList, version: ModelVersion[DenseLDA]): Unit
}

@Singleton
class LDARelatedLibraryUpdaterImpl @Inject() (
    db: Database,
    libTopicRepo: LibraryLDATopicRepo,
    relatedLibRepo: LDARelatedLibraryRepo) extends LDARelatedLibraryUpdater {

  private val TOP_K = 50
  private val MIN_WEIGHT = 0.6f

  // completely reconstruct an asymetric graph
  def update(version: ModelVersion[DenseLDA]): Unit = {
    val libTopics = db.readOnlyReplica { implicit s => libTopicRepo.getAllActiveByVersion(version) }
    libTopics.foreach { source =>
      val sourceId = source.libraryId
      val full = computeFullAdjacencyList(source, libTopics)
      val sparse = sparsify(full, TOP_K, MIN_WEIGHT)
      saveAdjacencyList(sourceId, sparse, version)
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

  def sparsify(adjacencyLists: AdjacencyList, topK: Int, minWeight: Float): AdjacencyList = {
    val kthLarge = adjacencyLists.map { _._2 }.sortBy(x => -x).take(topK).last
    val thresh = kthLarge max minWeight
    adjacencyLists.filter(_._2 >= thresh)
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

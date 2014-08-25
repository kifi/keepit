package com.keepit.graph.simple

import com.keepit.graph.model._
import java.util.concurrent.atomic.AtomicLong
import com.keepit.graph.manager.GraphStatistics
import com.keepit.common.logging.Logging
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

class SimpleGraphWriter(
    bufferedVertices: BufferedMap[VertexId, MutableVertex],
    vertexStatistics: Map[VertexType, AtomicLong],
    edgeStatistics: Map[(VertexType, VertexType, EdgeType), AtomicLong]) extends SimpleGraphReader(bufferedVertices) with GraphWriter with Logging {

  private val vertexDeltas = GraphStatistics.newVertexCounter()
  private val edgeDeltas: Map[(VertexType, VertexType, EdgeType), AtomicLong] = GraphStatistics.newEdgeCounter()

  private def getBufferedVertex(vertexId: VertexId): Option[MutableVertex] = bufferedVertices.get(vertexId) map {
    case alreadyBufferedVertex if bufferedVertices.containsUpdate(vertexId) => alreadyBufferedVertex
    case vertex => {
      val buffered = new MutableVertex(vertex.data, MutableOutgoingEdges(vertex.outgoingEdges), MutableIncomingEdges(vertex.incomingEdges))
      bufferedVertices += (vertexId -> buffered)
      buffered
    }
  }

  def saveVertex[V <: VertexDataReader](data: V): Boolean = {
    val vertexId = VertexId(data.id)(data.kind)
    getBufferedVertex(vertexId) match {
      case Some(bufferedVertex) =>
        bufferedVertex.data = data
        false
      case None =>
        bufferedVertices += (vertexId -> MutableVertex(data))
        vertexDeltas(data.kind).incrementAndGet()
        true
    }
  }

  def removeVertex[V <: VertexDataReader](vertex: VertexDataId[V])(implicit vertexKind: VertexKind[V]): Unit = {
    val vertexId = VertexId(vertex)
    Vertex.checkIfVertexExists(bufferedVertices)(vertexId)
    val bufferedVertex = getBufferedVertex(vertexId).get
    bufferedVertex.outgoingEdges.edges.foreach {
      case ((_, _, edgeKind), destinationVertexIdsWithData) =>
        destinationVertexIdsWithData.keys.foreach { destinationVertexId => removeEdge(vertexId, destinationVertexId, edgeKind) }
    }
    bufferedVertex.incomingEdges.edges.foreach {
      case ((_, _, edgeKind), sourceVertexIds) =>
        sourceVertexIds.foreach { sourceVertexId => removeEdge(sourceVertexId, vertexId, edgeKind) }
    }
    bufferedVertices -= vertexId
    vertexDeltas(vertexKind).decrementAndGet()
  }

  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    Vertex.checkIfVertexExists(bufferedVertices)(sourceVertexId)
    Vertex.checkIfVertexExists(bufferedVertices)(destinationVertexId)

    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get
    val isNewEdge = bufferedSourceVertex.saveOutgoingEdge(destinationVertexId, data)

    if (isNewEdge) {
      val bufferedDestinationVertex = getBufferedVertex(destinationVertexId).get
      bufferedDestinationVertex.addIncomingEdge(sourceVertexId, data.kind)
      val component = Component(sourceKind, destinationKind, data.kind)
      edgeDeltas(component).incrementAndGet()
    }
    Vertex.checkIfEdgeExists(bufferedVertices)(sourceVertexId, destinationVertexId, data.kind)
    isNewEdge
  }

  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit = {
    val sourceVertexId = VertexId(source)
    val destinationVertexId = VertexId(destination)
    removeEdge(sourceVertexId, destinationVertexId, edgeKind)
  }

  private def removeEdge(sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeType): Unit = {
    Vertex.checkIfEdgeExists(bufferedVertices)(sourceVertexId, destinationVertexId, edgeKind)

    val bufferedSourceVertex = getBufferedVertex(sourceVertexId).get
    bufferedSourceVertex.removeOutgoingEdge(destinationVertexId, edgeKind)

    val bufferedDestinationVertex = getBufferedVertex(destinationVertexId).get
    bufferedDestinationVertex.removeIncomingEdge(sourceVertexId, edgeKind)

    val component = (sourceVertexId.kind, destinationVertexId.kind, edgeKind)
    edgeDeltas(component).decrementAndGet()
  }

  def commit(): Unit = {
    try { bufferedVertices.updated.foreach { case (vertexId, updatedVertex) => SimpleGraph.checkVertexIntegrity(bufferedVertices, vertexId, updatedVertex) } }
    catch {
      case ex: Throwable =>
        log.error("Commit would leave the graph in a corrupt state.")
        log.error(ex.toString)
        log.error(s"Updated vertices: ${bufferedVertices.updated.keys.toSeq.mkString(", ")}")
        log.error(s"Removed vertices: ${bufferedVertices.removed.toSeq.mkString(", ")}")
        throw new IllegalStateException("Commit would leave the graph in a corrupt state.", ex)
    }
    val commitStatistics = GraphStatistics.filter(vertexDeltas, edgeDeltas)
    bufferedVertices.flush()
    vertexDeltas.foreach { case (vertexKind, counter) => vertexStatistics(vertexKind).addAndGet(counter.getAndSet(0)) }
    edgeDeltas.foreach { case (component, counter) => edgeStatistics(component).addAndGet(counter.getAndSet(0)) }
    log.info(s"Graph commit: $commitStatistics")
  }
}

package com.keepit.graph.model

import com.keepit.graph.model.EdgeKind.EdgeType

trait GraphReader {
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Boolean
  def removeVertex[V <: VertexDataReader](vertex: VertexDataId[V])(implicit vertexKind: VertexKind[V]): Unit
  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean
  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit
  def commit(): Unit

  def removeVertexIfExists[V <: VertexDataReader](vertex: VertexDataId[V])(implicit vertexKind: VertexKind[V]): Boolean = {
    try { removeVertex(vertex); true }
    catch { case VertexNotFoundException(_) => false }
  }

  def removeEdgeIfExists[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    try { removeEdge(source, destination, edgeKind); true }
    catch { case VertexNotFoundException(_) | EdgeNotFoundException(_, _, _) => false }
  }
}

case class VertexNotFoundException(vertexId: VertexId) extends Exception(s"Vertex $vertexId could not be found.")
case class EdgeNotFoundException(sourceId: VertexId, destinationId: VertexId, edgeKind: EdgeType) extends Exception(s"Edge of kind $edgeKind from $sourceId to $destinationId could not be found.")
case class UninitializedReaderException(message: String) extends Exception(message)

package com.keepit.graph.model

trait GraphReader {
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Boolean
  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E)(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean
  def removeEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit
  def removeEdgeIfExists[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Boolean = {
    try { removeEdge(source, destination, edgeKind); true }
    catch { case VertexNotFoundException(_) | EdgeNotFoundException(_, _) => false }
  }
  def commit(): Unit
}

case class VertexNotFoundException(vertexId: VertexId) extends Throwable(s"Vertex $vertexId could not be found.")
case class EdgeNotFoundException(sourceId: VertexId, destinationId: VertexId) extends Throwable(s"Edge from $sourceId to $destinationId could not be found.")
case class UninitializedReaderException(message: String) extends Throwable(message)

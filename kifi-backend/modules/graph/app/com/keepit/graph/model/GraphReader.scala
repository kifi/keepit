package com.keepit.graph.model

trait GraphReader {
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Boolean
  def saveEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E): Boolean
  def removeEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit
  def removeEdgeIfExists[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Boolean = {
    try { removeEdge(source, destination); true }
    catch { case VertexNotFoundException(_) | EdgeNotFoundException(_, _) => false }
  }
  def commit(): Unit
}

case class VertexNotFoundException(vertexId: VertexId) extends Throwable(s"Vertex $vertexId could not be found.")
case class EdgeNotFoundException(sourceId: VertexId, destinationId: VertexId) extends Throwable(s"Edge from $sourceId to $destinationId could not be found.")
case class UninitializedReaderException(message: String) extends Throwable(message)

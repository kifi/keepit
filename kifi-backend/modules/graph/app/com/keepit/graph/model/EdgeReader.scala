package com.keepit.graph.model

trait EdgeReader {
  def kind: EdgeKind[_ <: EdgeDataReader]
  def source: VertexId
  def destination: VertexId
  def data: EdgeDataReader
}

trait GlobalEdgeReader extends EdgeReader {
  def sourceVertex: VertexReader
  def destinationVertex: VertexReader
  def moveTo(source: VertexId, destination: VertexId): Unit
  def moveTo[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit
}

trait LocalEdgeReader extends EdgeReader {
  def sourceVertex: VertexReader
  def degree: Int
  def moveToNextEdge(): Boolean
  def reset(): Unit
}

class EdgeReaderException(message: String) extends Throwable(message)
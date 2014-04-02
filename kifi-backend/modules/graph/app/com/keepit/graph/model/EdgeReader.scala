package com.keepit.graph.model

trait EdgeReader {
  protected def kind: EdgeKind
  protected def dataReaders: Map[EdgeKind, EdgeDataReader]
  def source: VertexId
  def destination: VertexId
  def data: EdgeDataReader
  def sourceVertex: VertexReader
  def destinationVertex: VertexReader
}

trait GlobalEdgeReader extends EdgeReader {
  protected def moveTo(from: VertexId, to: VertexId): Unit
  def moveTo[S <: VertexDataReader, D <: VertexDataReader](from: VertexDataId[S], to: VertexDataId[D]): Unit
}

trait LocalEdgeReader extends EdgeReader {
  def sourceVertex: VertexReader
  def degree: Int
  def moveTo(num: Int): Unit
}

package com.keepit.graph.model

trait
EdgeReader {
  // protected def dataReaders: Map[EdgeKind, EdgeDataReader]
  def kind: EdgeKind[_ <: EdgeDataReader]
  def source: VertexId
  def destination: VertexId
  def data: EdgeDataReader
}

trait GlobalEdgeReader extends EdgeReader {
  protected def moveTo(from: VertexId, to: VertexId): Unit
  def moveTo[S <: VertexDataReader, D <: VertexDataReader](from: VertexDataId[S], to: VertexDataId[D]): Unit
  def sourceVertex: VertexReader
  def destinationVertex: VertexReader
}

trait LocalEdgeReader extends EdgeReader {
  def sourceVertex: VertexReader
  def degree: Int
  def moveToNextEdge(): Boolean
  def reset(): Unit
}

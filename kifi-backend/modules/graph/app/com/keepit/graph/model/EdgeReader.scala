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
  protected def moveTo(source: VertexId, destination: VertexId): Unit
  def moveTo[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit
  def sourceVertex: VertexReader
  def destinationVertex: VertexReader
}

trait LocalEdgeReader extends EdgeReader {
  def sourceVertex: VertexReader
  def degree: Int
  def moveToNextEdge(): Boolean
  def reset(): Unit
}

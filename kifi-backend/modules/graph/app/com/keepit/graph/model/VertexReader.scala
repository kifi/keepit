package com.keepit.graph.model

trait VertexReader {
  // protected def dataReaders: Map[VertexKind, VertexDataReader]
  def kind: VertexKind[_ <: VertexDataReader]
  def id: VertexId
  def data: VertexDataReader
  def edgeReader: LocalEdgeReader
}

trait GlobalVertexReader extends VertexReader {
  protected def moveTo(vertex: VertexId): Unit
  def moveTo[V <: VertexDataReader](vertex: VertexDataId[V]): Unit
}

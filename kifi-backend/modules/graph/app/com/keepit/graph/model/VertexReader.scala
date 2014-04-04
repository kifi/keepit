package com.keepit.graph.model

trait VertexReader {
  def kind: VertexKind[_ <: VertexDataReader]
  def id: VertexId
  def data: VertexDataReader
  def edgeReader: LocalEdgeReader
}

trait GlobalVertexReader extends VertexReader {
  def moveTo(vertex: VertexId): Unit
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit
}

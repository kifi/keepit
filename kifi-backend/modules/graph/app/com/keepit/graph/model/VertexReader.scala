package com.keepit.graph.model

case class VertexId(id: Long) extends AnyVal

trait VertexKind {
  type V <: VertexDataReader
}

trait VertexReader {
  protected def kind: VertexKind
  protected def dataReader(kind: VertexKind): Option[EdgeDataReader]
  def id: VertexId
  def data: VertexDataReader
  def edgeReader: LocalEdgeReader
}

trait GlobalVertexReader extends VertexReader {
  protected def moveTo(vertex: VertexId): Unit
  def moveTo[V <: VertexDataReader](vertexd: VertexDataId[V]): Unit
}

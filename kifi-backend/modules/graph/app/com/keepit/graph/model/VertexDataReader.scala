package com.keepit.graph.model

case class VertexDataId[V <: VertexDataReader](id: Long)

sealed trait VertexDataReader {
  type V <: VertexDataReader
  def id: VertexDataId[V]
  def dump: Array[Byte]
}

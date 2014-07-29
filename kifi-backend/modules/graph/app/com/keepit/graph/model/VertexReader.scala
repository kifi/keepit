package com.keepit.graph.model

import com.keepit.graph.model.VertexKind.VertexType

trait VertexReader {
  def kind: VertexType
  def id: VertexId
  def data: VertexDataReader
  def outgoingEdgeReader: OutgoingEdgeReader
  def incomingEdgeReader: IncomingEdgeReader
}

trait GlobalVertexReader extends VertexReader {
  def moveTo(vertex: VertexId): Unit
  def moveTo[V <: VertexDataReader: VertexKind](vertex: VertexDataId[V]): Unit
}

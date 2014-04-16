package com.keepit.graph.model

trait GraphReader {
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Boolean
  def saveEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E): Boolean
  def removeEdge[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Boolean
  def commit(): Unit
}



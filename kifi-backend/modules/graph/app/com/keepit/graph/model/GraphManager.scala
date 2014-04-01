package com.keepit.graph.model

trait GraphReader {
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
  def dump: Array[Byte]
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Boolean
  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E): Boolean
  def removeEdge[S <: VertexDataReader, D <: VertexDataReader](source: VertexDataId[S], destination: VertexDataId[D]): Boolean
  def commit(): Unit
}

trait GraphManager extends GraphReader {
  def write(f: GraphWriter => Unit): Unit
}

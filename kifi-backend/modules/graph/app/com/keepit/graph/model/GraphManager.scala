package com.keepit.graph.model

trait GraphReader {
  protected def resolve[V <: VertexDataReader](id: VertexDataId[V]): VertexId
  def getNewVertexReader(): GlobalVertexReader
  def getNewEdgeReader(): GlobalEdgeReader
  def dump: Array[Byte]
}

trait GraphWriter extends GraphReader {
  def saveVertex[V <: VertexDataReader](data: V): Unit
  def saveEdge[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], data: E): Unit
  def commit(): Unit
}

trait GraphManager extends GraphReader {
  def write(writer: GraphWriter => Unit): Unit
}

object GraphManager {
  def fromDump(dump: Array[Byte]): GraphManager = ???
}

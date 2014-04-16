package com.keepit.graph.model

import com.keepit.graph.ingestion.{GraphUpdaterState, GraphUpdate}

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

trait GraphManager {
  protected def readWrite[T](f: GraphWriter => T): T
  def readOnly[T](f: GraphReader => T): T
  def backup(): Unit
  def update(updates: GraphUpdate*): GraphUpdaterState
}

package com.keepit.graph.model

trait EdgeReader {
  def kind: EdgeKind[_ <: EdgeDataReader]
  def source: VertexId
  def destination: VertexId
  def data: EdgeDataReader
}

trait SourceReader { self: EdgeReader =>
  def sourceVertex: VertexReader
}

trait DestinationReader { self: EdgeReader =>
  def destinationVertex: VertexReader
}

trait GlobalEdgeReader extends EdgeReader with SourceReader with DestinationReader {
  def moveTo(source: VertexId, destination: VertexId): Unit
  def moveTo[S <: VertexDataReader: VertexKind, D <: VertexDataReader: VertexKind](source: VertexDataId[S], destination: VertexDataId[D]): Unit
}

trait OutgoingEdgeReader extends EdgeReader with SourceReader {
  def degree: Int
  def moveToNextEdge(): Boolean
  def reset(): Unit
}

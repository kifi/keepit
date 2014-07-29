package com.keepit.graph.model

import com.keepit.graph.model.Component.Component
import com.keepit.graph.model.EdgeKind.EdgeType

trait EdgeReader {
  def kind: EdgeType
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
  def moveTo[S <: VertexDataReader, D <: VertexDataReader, E <: EdgeDataReader](source: VertexDataId[S], destination: VertexDataId[D], edgeKind: EdgeKind[E])(implicit sourceKind: VertexKind[S], destinationKind: VertexKind[D]): Unit
}

trait LocalEdgeReader extends EdgeReader {
  def moveToNextComponent(): Boolean
  def component: Component
  def degree: Int
  def moveToNextEdge(): Boolean
  def reset(): Unit
}

trait OutgoingEdgeReader extends LocalEdgeReader with SourceReader
trait IncomingEdgeReader extends LocalEdgeReader with DestinationReader

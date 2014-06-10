package com.keepit.graph.wander

import com.keepit.graph.model._
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

trait EdgeResolver {
  def weightComponent(source: VertexReader, destinationKind: VertexType, edgeKind: EdgeType): Double
  def weightEdge(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double
}

case class RestrictedDestinationResolver(mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean) extends EdgeResolver {
  def weightComponent(source: VertexReader, destinationKind: VertexType, edgeKind: EdgeType): Double = 1
  def weightEdge(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double = {
    if (!mayTraverse(source, destination, edge)) { 0 }
    else edge.data match {
      case _: EmptyEdgeReader => 1.0
      case weightedEdge: WeightedEdgeReader => weightedEdge.weight
    }
  }
}

package com.keepit.graph.wander

import com.keepit.graph.model._
import com.keepit.graph.model.Component.Component

trait EdgeResolver {
  def weightComponent(component: Component): Double
  def weightEdge(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double
}

case class RestrictedDestinationResolver(
    subgraph: Option[Set[Component]],
    mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean,
    decay: TimestampEdgeReader => Double) extends EdgeResolver {
  def weightComponent(component: Component): Double = if (subgraph.isEmpty || subgraph.exists(_.contains(component))) 1 else 0
  def weightEdge(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double = {
    if (!mayTraverse(source, destination, edge)) { 0 }
    else edge.data match {
      case _: EmptyEdgeReader => 1.0
      case timestamp: TimestampEdgeReader => decay(timestamp)
      case weightedEdge: WeightedEdgeReader => weightedEdge.weight
    }
  }
}

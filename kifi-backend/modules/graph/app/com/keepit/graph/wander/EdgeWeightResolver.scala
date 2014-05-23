package com.keepit.graph.wander

import com.keepit.graph.model._

trait EdgeWeightResolver {
  def weight(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double
}

case class RestrictedDestinationResolver(mayTraverse: (VertexReader, VertexReader, EdgeReader) => Boolean) extends EdgeWeightResolver {
  def weight(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double = {
    if (!mayTraverse(source, destination, edge)) { 0 }
    else edge.data match {
      case _: EmptyEdgeReader => 1.0 / destination.edgeReader.degree
      case weightedEdge: WeightedEdgeReader => weightedEdge.getWeight
    }
  }
}

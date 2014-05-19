package com.keepit.graph.wander

import com.keepit.graph.model._

trait EdgeWeightResolver {
  def weight(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double
}

class RestrictedDestinationsResolver(authorizedDestinations: Set[VertexKind[_ <: VertexDataReader]]) extends EdgeWeightResolver {
  def weight(source: VertexReader, destination: VertexReader, edge: EdgeReader): Double = {
    if (!authorizedDestinations.contains(destination.kind)) { 0 }
    else edge.data match {
      case _: EmptyEdgeReader => 1
      case weightedEdge: WeightedEdgeReader => weightedEdge.getWeight
    }
  }
}

package com.keepit.graph.model

import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType

object Component {
  type Component = (VertexType, VertexType, EdgeType)
  val all: Set[Component] = for {
    sourceKind <- VertexKind.all
    destinationKind <- VertexKind.all
    edgeKind <- EdgeKind.all
  } yield (sourceKind, destinationKind, edgeKind)

  private val indexed: Map[VertexType, Map[VertexType, Map[EdgeType, Component]]] = {
    all.groupBy { case (sourceKind, _, _) => sourceKind }.mapValues { componentsBySource =>
      componentsBySource.groupBy { case (_, destinationKind, _) => destinationKind }.mapValues { componentsBySourceAndDestination =>
        componentsBySourceAndDestination.map { case component @ (_, _, edgeKind) => edgeKind -> component }.toMap
      }
    }
  }

  def apply(sourceKind: VertexType, destinationKind: VertexType, edgeKind: EdgeType): Component = {
    indexed(sourceKind)(destinationKind)(edgeKind)
  }
}

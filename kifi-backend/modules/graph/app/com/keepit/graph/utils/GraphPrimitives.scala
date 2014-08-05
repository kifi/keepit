package com.keepit.graph.utils

import com.keepit.graph.model.{ VertexId, GlobalVertexReader }
import com.keepit.graph.model.VertexKind._
import scala.collection.mutable

object GraphPrimitives {

  def collectOutgoingNeighbors(vertexReader: GlobalVertexReader)(vertexId: VertexId, neighborKinds: Set[VertexType]): Set[VertexId] = {
    vertexReader.moveTo(vertexId)
    val neighbors = mutable.Set[VertexId]()
    while (vertexReader.outgoingEdgeReader.moveToNextComponent()) {
      val (_, destinationKind, _) = vertexReader.outgoingEdgeReader.component
      if (neighborKinds.contains(destinationKind)) {
        while (vertexReader.outgoingEdgeReader.moveToNextEdge()) {
          neighbors += vertexReader.outgoingEdgeReader.destination
        }
      }
    }
    neighbors.toSet
  }
}

package com.keepit.graph.utils

import com.keepit.graph.model.Component.Component
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

trait NeighborQuerier {
  def getNeighbors(reader: GlobalVertexReader, component: Component, outGoing: Boolean): Set[VertexId] = {
    val edgeReader = if (outGoing) {
      reader.outgoingEdgeReader.reset()
      reader.outgoingEdgeReader
    } else {
      reader.incomingEdgeReader.reset()
      reader.incomingEdgeReader
    }

    val nbs = mutable.Set[VertexId]()

    while (edgeReader.moveToNextComponent()) {
      if (edgeReader.component == component) {
        while (edgeReader.moveToNextEdge()) {
          nbs += edgeReader.destination
        }
      }
    }
    nbs.toSet
  }
}

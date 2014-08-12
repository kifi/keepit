package com.keepit.graph.simple

import com.keepit.graph.model._
import scala.collection.{ Map, Set }
import com.keepit.graph.model.VertexKind.VertexType
import com.keepit.graph.model.EdgeKind.EdgeType
import com.keepit.graph.model.EdgeNotFoundException
import com.keepit.graph.model.VertexNotFoundException

trait Vertex {
  def data: VertexDataReader
  def outgoingEdges: OutgoingEdges
  def incomingEdges: IncomingEdges
}

trait OutgoingEdges {
  def edges: Map[(VertexType, VertexType, EdgeType), Map[VertexId, EdgeDataReader]]
}

trait IncomingEdges {
  def edges: Map[(VertexType, VertexType, EdgeType), Set[VertexId]]
}

object Vertex {
  def checkIfVertexExists(vertices: Map[VertexId, Vertex])(vertexId: VertexId): Unit = {
    if (!vertices.contains(vertexId)) { throw new VertexNotFoundException(vertexId) }
  }

  def checkIfEdgeExists(vertices: Map[VertexId, Vertex])(sourceVertexId: VertexId, destinationVertexId: VertexId, edgeKind: EdgeType): Unit = {
    checkIfVertexExists(vertices)(sourceVertexId)
    checkIfVertexExists(vertices)(destinationVertexId)

    val component = (sourceVertexId.kind, destinationVertexId.kind, edgeKind)
    val sourceVertex = vertices(sourceVertexId)
    val destinationVertex = vertices(destinationVertexId)

    val outgoingEdgeExists = sourceVertex.outgoingEdges.edges.contains(component) && sourceVertex.outgoingEdges.edges(component).contains(destinationVertexId)
    val incomingEdgeExists = destinationVertex.incomingEdges.edges.contains(component) && destinationVertex.incomingEdges.edges(component).contains(sourceVertexId)

    (outgoingEdgeExists, incomingEdgeExists) match {
      case (true, true) => // all good
      case (false, false) => throw new EdgeNotFoundException(sourceVertexId, destinationVertexId, edgeKind)
      case (true, false) => throw new IllegalStateException(s"Could not find incoming edge matching outgoing edge ${(sourceVertexId, destinationVertexId, edgeKind)}")
      case (false, true) => throw new IllegalStateException(s"Could not find outgoing edge matching incoming edge ${(sourceVertexId, destinationVertexId, edgeKind)}")
    }
  }
}

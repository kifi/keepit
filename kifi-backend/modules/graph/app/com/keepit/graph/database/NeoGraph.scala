package com.keepit.graph.database

import com.keepit.graph.model._
import org.neo4j.graphdb._

class NeoGraph[V, E](graphDb: GraphDatabaseService) extends Graph[V, E] {

  def createVertices[U <: V <% TypeProvider[U]](verticesData: U*) = verticesData.map { data =>
    val node = graphDb.createNode()
    node.setProperty("type", data.typeCode)
    node.setProperty("data", data)
    NeoVertex[U](node)
  }

  def getVertices[U <: V](vertexIds: VertexId[U]*) = vertexIds.map { id =>
    val node = graphDb.getNodeById(id.id)
    NeoVertex[U](node)
  }

  def createEdges[S <: V, D <: V, F <: E <% TypeProvider[F]](edges: (VertexId[S], VertexId[D], F)*) = edges.map { case (source, destination, data) =>
    val sourceNode = graphDb.getNodeById(source.id)
    val destinationNode = graphDb.getNodeById(destination.id)
    val relationshipType = DynamicRelationshipType.withName(data.typeCode.toString())
    val relationship = sourceNode.createRelationshipTo(destinationNode, relationshipType)
    NeoEdge[S, D, F](relationship)
  }
}

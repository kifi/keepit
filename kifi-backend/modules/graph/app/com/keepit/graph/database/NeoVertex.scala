package com.keepit.graph.database

import com.keepit.graph.model._
import org.neo4j.graphdb._
import scala.collection.JavaConversions._

case class NeoVertex[+V](node: Node) extends Vertex[V] {
  def id = VertexId[V](node.getId())
  def data = node.getProperty("data").asInstanceOf[V]

  def outgoingEdges[D, E](edgeTypes: TypeProvider[E]*) = {
    val relationshipTypes = edgeTypes.map { edgeType => DynamicRelationshipType.withName(edgeType.typeCode.toString()) }
    node.getRelationships(Direction.OUTGOING, relationshipTypes:_*).map(NeoEdge[V, D, E](_)).toSeq
  }

  def incomingEdges[S, E](edgeTypes: TypeProvider[E]*) = {
    val relationshipTypes = edgeTypes.map { edgeType => DynamicRelationshipType.withName(edgeType.typeCode.toString()) }
    node.getRelationships(Direction.INCOMING, relationshipTypes:_*).map(NeoEdge[S, V, E](_)).toSeq
  }
}

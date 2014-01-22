package com.keepit.graph.database

import com.keepit.graph.model._
import org.neo4j.graphdb._
import play.api.libs.json.{Format, Json}
import scala.collection.JavaConversions._

case class NeoVertex[+T : Companion](node: Node) extends Vertex[T] {

  def id = VertexId[T](node.getProperty("id").asInstanceOf[Long])

  def data = {
    val jsonData = Json.parse(node.getProperty("data").asInstanceOf[String])
    implicitly[Companion[T]].format.reads(jsonData).get
  }

  def outgoingEdges[V >: T, E](edgeDataTypes: Companion[_ <: E]*)(implicit graph: Graph[V, E]) = {
    require(graph.isInstanceOf[NeoGraph[V, E]])
    val relationshipTypes = edgeDataTypes.map { edgeDataType => DynamicRelationshipType.withName(edgeDataType.typeCode.toString()) }
    val relationships = node.getRelationships(Direction.OUTGOING, relationshipTypes:_*).toSeq
    relationships.map(graph.asInstanceOf[NeoGraph[V, E]].outgoingNeoEdge[T](_))
  }

  def incomingEdges[V >: T, E](edgeDataTypes: Companion[_ <: E]*)(implicit graph: Graph[V, E]) = {
    require(graph.isInstanceOf[NeoGraph[V, E]])
    val relationshipTypes = edgeDataTypes.map { edgeDataType => DynamicRelationshipType.withName(edgeDataType.typeCode.toString()) }
    val relationships = node.getRelationships(Direction.INCOMING, relationshipTypes:_*).toSeq
    relationships.map(graph.asInstanceOf[NeoGraph[V, E]].incomingNeoEdge[T](_))
  }
}

trait NeoVertexFactory[V] {
  def neoVertex(node: Node): NeoVertex[V]
}

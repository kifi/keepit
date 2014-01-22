package com.keepit.graph.database

import org.neo4j.graphdb.Relationship
import com.keepit.graph.model._
import play.api.libs.json.Json
import com.keepit.graph.model.VertexId

case class NeoEdge[+S : Companion, +D : Companion, +T : Companion](relationship: Relationship) extends Edge[S, D, T] {

  def sourceId = VertexId[S](relationship.getProperty("sourceId").asInstanceOf[Long])
  def destinationId = VertexId[D](relationship.getProperty("destinationId").asInstanceOf[Long])
  def data = {
    val jsonData = Json.parse(relationship.getProperty("data").asInstanceOf[String])
    implicitly[Companion[T]].format.reads(jsonData).get
  }

  def source = NeoVertex[S](relationship.getStartNode())
  def destination = NeoVertex[D](relationship.getEndNode())
}

trait NeoEdgeFactory[V, E] {
  def neoEdge(relationship: Relationship): NeoEdge[V, V, E]
  def outgoingNeoEdge[S <: V](relationship: Relationship): NeoEdge[S, V, E] = neoEdge(relationship).asInstanceOf[NeoEdge[S, V, E]]
  def incomingNeoEdge[D <: V](relationship: Relationship): NeoEdge[V, D, E] = neoEdge(relationship).asInstanceOf[NeoEdge[V, D, E]]
}

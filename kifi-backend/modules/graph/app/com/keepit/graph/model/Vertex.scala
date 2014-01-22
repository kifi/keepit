package com.keepit.graph.model

import play.api.libs.json._
import org.neo4j.graphdb.Node
import com.keepit.graph.database.{NeoVertexFactory, NeoVertex}

trait Vertex[+T] {
  def id: VertexId[T]
  def data: T

  def outgoingEdges[V >: T, E](edgeTypes: Companion[_ <: E]*)(implicit graph: Graph[V, E]): Seq[Edge[T, V, E]]
  def incomingEdges[V >: T, E](edgeTypes: Companion[_ <: E]*)(implicit graph: Graph[V, E]): Seq[Edge[V, T, E]]
}

case class VertexId[+V](id: Long) {
  override def toString() = id.toString
}

trait VertexData

object VertexData extends NeoVertexFactory[VertexData] {
  def neoVertex(node: Node) = {
    Companion.fromTypeCodeString(node.getProperty("type").asInstanceOf[String]) match {
      case UserData => NeoVertex[UserData](node)
      case CollectionData => NeoVertex[CollectionData](node)
      case UriData => NeoVertex[UriData](node)
    }
  }
}

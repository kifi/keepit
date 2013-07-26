package com.keepit.graph.model

import play.api.libs.json._

trait Vertex[+V] {
  def id: VertexId[V]
  def data: V
  def outgoingEdges[D, E](edgeTypes: TypeProvider[E]*): Seq[Edge[V, D, E]]
  def incomingEdges[S, E](edgeTypes: TypeProvider[E]*): Seq[Edge[S, V, E]]
}

case class VertexId[+V](id: Long) {
  override def toString = id.toString
}

object VertexId {

  def format[V]: Format[VertexId[V]] =
    Format(__.read[Long].map(VertexId(_)), new Writes[VertexId[V]]{ def writes(o: VertexId[V]) = JsNumber(o.id) })
}

trait VertexData

object VertexData {
  def typeCode(data: VertexData): TypeCode[VertexData] = data match {
    case data: UserData => UserData.typeCode
    case data: CollectionData => CollectionData.typeCode
    case data: UriData => UriData.typeCode
  }
}

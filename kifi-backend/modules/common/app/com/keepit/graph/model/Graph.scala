package com.keepit.graph.model

import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.reflect.runtime.universe._

case class Graph[V <: VertexData, E <: EdgeData](vertices: Seq[Vertex[V]], edges: Seq[Edge[V, V, E]])

case class Vertex[+V <: VertexData](id: VertexId[V], data: V)

case class Edge[S <: VertexData, D <: VertexData, +E <: EdgeData](source: VertexId[S], destination: VertexId[D], data: E) {
  def reversed() = Edge[D, S, E](destination, source, data)
}

object Vertex {

  implicit def format[V <: VertexData]()(implicit tag: TypeTag[V]): Format[Vertex[V]] = (
    (__ \ 'id).format(VertexId.format[V]) and
    (__ \ 'data).format(VertexData.format[V]())
    )(Vertex.apply, unlift(Vertex.unapply))
}

object Edge {

  implicit def format[S <: VertexData, D <: VertexData, E <: EdgeData]()(implicit sourceTag: TypeTag[S], destinationTag: TypeTag[D], edgeTag: TypeTag[E]): Format[Edge[S, D, E]] = (
    (__ \ 'source).format(VertexId.format[S]) and
    (__ \ 'destination).format(VertexId.format[D]) and
    (__ \ 'data).format(EdgeData.format[E]())
    )(Edge.apply, unlift(Edge.unapply))
}

package com.keepit.graph.model

case class Graph[V <: VertexData, E <: EdgeData](vertices: Seq[Vertex[V]], edges: Seq[Edge[V, V, E]])

case class Vertex[+V <: VertexData](id: VertexId[V], data: V)

case class Edge[S <: VertexData, D <: VertexData, +E <: EdgeData](source: VertexId[S], destination: VertexId[D], data: E) {
  def reversed() = Edge[D, S, E](destination, source, data)
}

package com.keepit.graph.model

case class Graph(vertices: Seq[Vertex], edges: Seq[Edge])

trait Vertex {
  val id: VertexId
  val data: VertexData
}

case class RealVertex[T](id: RealVertexId[T], data: RealVertexData[T]) extends Vertex

trait Edge {
  val source: VertexId
  val destination: VertexId
  val data: EdgeData
}

case class RealEdge[S, D, E](source: RealVertexId[S], destination: RealVertexId[D], data: RealEdgeData[E]) extends Edge {
  def reversed() = RealEdge[D, S, E](destination, source, data)
}

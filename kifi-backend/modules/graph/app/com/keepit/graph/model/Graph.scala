package com.keepit.graph.model

trait Graph[V, E] {
  def createVertices[U <: V <% TypeProvider[U]](verticesData: U*): Seq[Vertex[U]]
  def createEdges[S <: V, D <: V, F <: E <% TypeProvider[F]](edges: (VertexId[S], VertexId[D], F)*): Seq[Edge[S, D, F]]
  def getVertices[U <: V](vertexIds: VertexId[U]*): Seq[Vertex[U]]
}

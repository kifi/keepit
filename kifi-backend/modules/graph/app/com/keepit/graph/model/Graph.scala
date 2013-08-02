package com.keepit.graph.model

trait Graph[V, E] {
  def createVertices[T <: V : Companion](vertices : (VertexId[T], T)*): Seq[Vertex[T]]
  def createEdges[S <: V : Companion, D <: V : Companion, T <: E : Companion](edges: (VertexId[S], VertexId[D], T)*): Seq[Edge[S, D, T]]
  def createEdges[S <: V : Companion, D <: V : Companion, T <: E : Companion](edges: (Vertex[S], Vertex[D], T)*): Seq[Edge[S, D, T]]
  def getVertices[T <: V](vertexIds: VertexId[T]*): Seq[Vertex[T]]
}

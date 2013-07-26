package com.keepit.graph.model

trait Edge[+S, +D, +E] {
  def source: Vertex[S]
  def destination: Vertex[D]
  def data: E
}

trait EdgeData

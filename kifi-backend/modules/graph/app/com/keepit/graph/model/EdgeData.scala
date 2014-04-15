package com.keepit.graph.model


sealed trait EdgeData[E <: EdgeDataReader] { self: E =>
  def asReader: E = self
}

case object EmptyEdgeData extends EmptyEdgeDataReader with EdgeData[EmptyEdgeDataReader]

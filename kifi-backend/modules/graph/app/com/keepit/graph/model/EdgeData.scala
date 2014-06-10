package com.keepit.graph.model


sealed trait EdgeData[E <: EdgeDataReader] { self: E =>
  def asReader: E = self
}

case object EmptyEdgeData extends EmptyEdgeReader with EdgeData[EmptyEdgeReader]

case class WeightedEdgeData(weight: Float) extends WeightedEdgeReader with EdgeData[WeightedEdgeReader]

case class TimestampData(timestamp: Long) extends TimestampReader with EdgeData[TimestampReader]

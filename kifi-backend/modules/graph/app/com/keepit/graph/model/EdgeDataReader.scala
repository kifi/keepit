package com.keepit.graph.model

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def dump: Array[Byte]
}

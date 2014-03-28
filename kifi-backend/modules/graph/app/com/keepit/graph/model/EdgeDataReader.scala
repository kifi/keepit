package com.keepit.graph.model

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def dump: Array[Byte]
}

object EdgeDataReader {
  def apply(rawDataReader: RawDataReader): Map[EdgeKind, EdgeDataReader] = {
    EdgeKind.all.map { edgeKind => edgeKind -> edgeKind(rawDataReader) }.toMap
  }
}

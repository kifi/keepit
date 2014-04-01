package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def dump: Array[Byte]
}

object EdgeDataReader {
  def apply(rawDataReader: RawDataReader): Map[EdgeKind[_ <: EdgeDataReader], EdgeDataReader] = {
    EdgeKind.all.map { edgeKind => edgeKind -> edgeKind(rawDataReader) }.toMap
  }
}

sealed trait EdgeKind[E <: EdgeDataReader] {
  implicit def header: KindHeader[E]
  def apply(rawDataReader: RawDataReader): E
}

object EdgeKind {
  val all: Set[EdgeKind[_ <: EdgeDataReader]] = CompanionTypeSystem[EdgeDataReader, EdgeKind[_ <: EdgeDataReader]]("E")
  private val byHeader: Map[Byte, EdgeKind[_ <: EdgeDataReader]] = {
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header.code -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeKind[_ <: EdgeDataReader] = byHeader(header)
}

trait NoEdgeDataReader extends EdgeDataReader {
  type E = NoEdgeDataReader
  val dump = Array.empty[Byte]
}

case object NoEdgeDataReader extends EdgeKind[NoEdgeDataReader] with NoEdgeDataReader {
  val header = KindHeader[E](1)
  def apply(rawDataReader: RawDataReader): E = this
}

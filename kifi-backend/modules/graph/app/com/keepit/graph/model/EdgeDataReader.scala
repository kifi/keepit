package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def kind: EdgeKind[E]
}

object EdgeDataReader {
  def apply(rawDataReader: RawDataReader): Map[EdgeKind[_ <: EdgeDataReader], EdgeDataReader] = {
    EdgeKind.all.map { edgeKind => edgeKind -> edgeKind(rawDataReader) }.toMap
  }
}

sealed trait EdgeKind[E <: EdgeDataReader] {
  implicit def kind: EdgeKind[E] = this
  implicit def header: Byte
  def apply(rawDataReader: RawDataReader): E
  def dump(data: E): Array[Byte]
}

object EdgeKind {
  val all: Set[EdgeKind[_ <: EdgeDataReader]] = CompanionTypeSystem[EdgeDataReader, EdgeKind[_ <: EdgeDataReader]]("E")
  private val byHeader: Map[Byte, EdgeKind[_ <: EdgeDataReader]] = {
    require(all.forall(_.header > 0), "EdgeKind headers must be positive.")
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeKind[_ <: EdgeDataReader] = byHeader(header)
}

trait EmptyEdgeDataReader extends EdgeDataReader {
  type E = EmptyEdgeDataReader
}

case object EmptyEdgeDataReader extends EdgeKind[EmptyEdgeDataReader] with EmptyEdgeDataReader {
  val header = 1.toByte
  def apply(rawDataReader: RawDataReader): EmptyEdgeDataReader = this
  def dump(data: EmptyEdgeDataReader): Array[Byte] = Array.empty
}

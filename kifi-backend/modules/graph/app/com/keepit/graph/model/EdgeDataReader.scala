package com.keepit.graph.model

import com.keepit.model.Reflect

sealed trait EdgeKind {
  type E <: EdgeDataReader
  def header: Byte
  def apply(rawDataReader: RawDataReader): E
}

object EdgeKind {
  val all: Set[EdgeKind] = Reflect.getCompanionTypeSystem[EdgeDataReader, EdgeKind]("E")
  private val byHeader = {
    require(all.size == all.map(_.header).size, "Duplicate EdgeKind headers")
    all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
  }
  def apply(header: Byte): EdgeKind = byHeader(header)
}

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def dump: Array[Byte]
}

object EdgeDataReader {
  def apply(rawDataReader: RawDataReader): Map[EdgeKind, EdgeDataReader] = EdgeKind.all.map { edgeKind =>
    edgeKind -> edgeKind(rawDataReader)
  }.toMap
}


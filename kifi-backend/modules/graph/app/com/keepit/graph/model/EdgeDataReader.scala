package com.keepit.graph.model

import com.keepit.graph._

sealed trait EdgeKind {
  type E <: EdgeDataReader
  def header: Byte
  def apply(rawDataReader: RawDataReader): E
}

object EdgeKind {
  val all: Set[EdgeKind] = {
    val kinds: Set[EdgeKind] = getSubclasses[EdgeKind].map(getCompanion(_).asInstanceOf[EdgeKind])
    require(kinds.size == kinds.map(_.header).size, "Duplicate EdgeKind headers")
    kinds
  }

  private val byHeader = all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
  def apply(header: Byte): EdgeKind = byHeader(header)
}

sealed trait EdgeDataReader {
  type E <: EdgeDataReader
  def dump: Array[Byte]
}

object EdgeDataReader {
  checkDataReaderCompanions[EdgeDataReader, EdgeKind]
  def apply(rawDataReader: RawDataReader): Map[EdgeKind, EdgeDataReader] = EdgeKind.all.map { edgeKind =>
    edgeKind -> edgeKind(rawDataReader)
  }.toMap
}


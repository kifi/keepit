package com.keepit.graph.model

sealed trait EdgeKind {
  type E <: EdgeDataReader
  def header: Byte
  def apply(rawDataReader: RawDataReader): E
}

object EdgeKind {
  import scala.reflect.runtime.universe._
  private val m = runtimeMirror(getClass.getClassLoader)
  val all: Set[EdgeKind] = {
    val edgeKinds = typeOf[EdgeKind].typeSymbol.asClass.knownDirectSubclasses.map { subclass =>
      m.reflectModule(subclass.asClass.companionSymbol.asModule).instance.asInstanceOf[EdgeKind]
    }
    require(edgeKinds.size == edgeKinds.map(_.header).size, "Duplicate EdgeKind headers")
    edgeKinds
  }
  private val byHeader = all.map { edgeKind => edgeKind.header -> edgeKind }.toMap
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


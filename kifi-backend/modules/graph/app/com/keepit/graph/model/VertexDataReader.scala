package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem


sealed trait VertexDataReader {
  type V <: VertexDataReader
  def id: VertexDataId[V]
  def dump: Array[Byte]
}

object VertexDataReader {
  def apply(rawDataReader: RawDataReader): Map[VertexKind[_ <: VertexDataReader], VertexDataReader] = {
    VertexKind.all.map { vertexKind => vertexKind -> vertexKind(rawDataReader) }.toMap
  }
}

sealed trait VertexKind[V <: VertexDataReader] {
  implicit def header: KindHeader[V]
  def apply(rawDataReader: RawDataReader): V
}

object VertexKind {
  val all: Set[VertexKind[_ <: VertexDataReader]] = CompanionTypeSystem[VertexDataReader, VertexKind[_ <: VertexDataReader]]("V")
  private val byHeader: Map[Byte, VertexKind[_ <: VertexDataReader]] = {
    require(all.size == all.map(_.header).size, "Duplicate VertexKind headers")
    all.map { vertexKind => vertexKind.header.code -> vertexKind }.toMap
  }
  def apply(header: Byte): VertexKind[_ <: VertexDataReader] = byHeader(header)
}

trait UserReader extends VertexDataReader {
  type V = UserReader
  def dump = Array.empty
}
case object UserReader extends VertexKind[UserReader] {
  val header = KindHeader[UserReader](1)
  def apply(rawDataReader: RawDataReader): UserReader = ???
}

trait UriReader extends VertexDataReader {
  type V = UriReader
  def dump = Array.empty
}
case object UriReader extends VertexKind[UriReader] {
  val header = KindHeader[UriReader](2)
  def apply(rawDataReader: RawDataReader): UriReader = ???
}

trait TagReader extends VertexDataReader {
  type V = TagReader
  def dump = Array.empty
}
case object TagReader extends VertexKind[TagReader] {
  val header = KindHeader[TagReader](3)
  def apply(rawDataReader: RawDataReader): TagReader = ???
}

trait ThreadReader extends VertexDataReader {
  type V = ThreadReader
  def dump = Array.empty
}
case object ThreadReader extends VertexKind[ThreadReader] {
  val header = KindHeader[ThreadReader](4)
  def apply(rawDataReader: RawDataReader): ThreadReader = ???
}

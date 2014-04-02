package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem


sealed trait VertexDataReader {
  type V <: VertexDataReader
  def kind: VertexKind[V]
  def id: VertexDataId[V]
}

object VertexDataReader {
  def apply(rawDataReader: RawDataReader): Map[VertexKind[_ <: VertexDataReader], VertexDataReader] = {
    VertexKind.all.map { vertexKind => vertexKind -> vertexKind(rawDataReader) }.toMap
  }
}

sealed trait VertexKind[V <: VertexDataReader] {
  implicit def kind: VertexKind[V] = this
  implicit def header: KindHeader[V]
  def apply(rawDataReader: RawDataReader): V
  def dump(data: V): Array[Byte]
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
  def kind = UserReader
}
case object UserReader extends VertexKind[UserReader] {
  val header = KindHeader[UserReader](1)
  def apply(rawDataReader: RawDataReader): UserReader = ???
  def dump(data: UserReader): Array[Byte] = ???
}

trait UriReader extends VertexDataReader {
  type V = UriReader
  def kind = UriReader
}
case object UriReader extends VertexKind[UriReader] {
  val header = KindHeader[UriReader](2)
  def apply(rawDataReader: RawDataReader): UriReader = ???
  def dump(data: UriReader): Array[Byte] = ???
}

trait TagReader extends VertexDataReader {
  type V = TagReader
  def kind = TagReader
}
case object TagReader extends VertexKind[TagReader] {
  val header = KindHeader[TagReader](3)
  def apply(rawDataReader: RawDataReader): TagReader = ???
  def dump(data: TagReader): Array[Byte] = ???
}

trait ThreadReader extends VertexDataReader {
  type V = ThreadReader
  def kind = ThreadReader
}
case object ThreadReader extends VertexKind[ThreadReader] {
  val header = KindHeader[ThreadReader](4)
  def apply(rawDataReader: RawDataReader): ThreadReader = ???
  def dump(data: ThreadReader): Array[Byte] = ???
}

package com.keepit.graph.model

sealed trait VertexKind {
  type V <: VertexDataReader
  def header: Byte
  def apply(rawDataReader: RawDataReader): V
}

object VertexKind {
  val all: Set[VertexKind] = {
    val kinds: Set[VertexKind] = Reflect.getSubclasses[VertexKind].map(Reflect.getCompanion(_).asInstanceOf[VertexKind])
    require(kinds.size == kinds.map(_.header).size, "Duplicate VertexKind headers")
    kinds
  }

  private val byHeader = all.map { vertexKind => vertexKind.header -> vertexKind }.toMap
  def apply(header: Byte): VertexKind = byHeader(header)
}

case class VertexDataId[V <: VertexDataReader](id: Long) // extends AnyVal

sealed trait VertexDataReader {
  type V <: VertexDataReader
  def id: VertexDataId[V]
  def dump: Array[Byte]
}

object VertexDataReader {
  Reflect.checkDataReaderCompanions[VertexDataReader, VertexKind]
  def apply(rawDataReader: RawDataReader): Map[VertexKind, VertexDataReader] = VertexKind.all.map { vertexKind =>
    vertexKind -> vertexKind(rawDataReader)
  }.toMap
}

trait UserDataReader extends VertexDataReader { type V = UserDataReader }
object UserDataReader extends VertexKind {
  type V = UserDataReader
  val header = 0.toByte
  def apply(rawDataReader: RawDataReader): V = ???
}

trait UriDataReader extends VertexDataReader { type V = UriDataReader }
object UriDataReader extends VertexKind {
  type V = UriDataReader
  val header = 1.toByte
  def apply(rawDataReader: RawDataReader): V = ???
}

trait TagDataReader extends VertexDataReader { type V = TagDataReader }
object TagDataReader extends VertexKind {
  type V = TagDataReader
  val header = 2.toByte
  def apply(rawDataReader: RawDataReader): V = ???
}

trait ThreadDataReader extends VertexDataReader { type V = ThreadDataReader }
object ThreadDataReader extends VertexKind {
  type V = ThreadDataReader
  val header = 3.toByte
  def apply(rawDataReader: RawDataReader): V = ???
}


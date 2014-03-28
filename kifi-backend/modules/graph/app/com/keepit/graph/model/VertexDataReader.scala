package com.keepit.graph.model

case class VertexDataId[V <: VertexDataReader](id: Long) // extends AnyVal

sealed trait VertexDataReader {
  type V <: VertexDataReader
  def id: VertexDataId[V]
  def dump: Array[Byte]
}

object VertexDataReader {
  def apply(rawDataReader: RawDataReader): Map[VertexKind, VertexDataReader] = VertexKind.all.map { vertexKind =>
    vertexKind -> vertexKind(rawDataReader)
  }.toMap
}

trait UserDataReader extends VertexDataReader { type V = UserDataReader }
case object UserDataReader extends VertexKind {
  type V = UserDataReader
  val header = KindHeader[V](1)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait UriDataReader extends VertexDataReader { type V = UriDataReader }
case object UriDataReader extends VertexKind {
  type V = UriDataReader
  val header = KindHeader[V](2)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait TagDataReader extends VertexDataReader { type V = TagDataReader }
case object TagDataReader extends VertexKind {
  type V = TagDataReader
  val header = KindHeader[V](3)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait ThreadDataReader extends VertexDataReader { type V = ThreadDataReader }
case object ThreadDataReader extends VertexKind {
  type V = ThreadDataReader
  val header = KindHeader[V](4)
  def apply(rawDataReader: RawDataReader): V = ???
}

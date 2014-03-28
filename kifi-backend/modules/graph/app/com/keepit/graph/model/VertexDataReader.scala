package com.keepit.graph.model

case class VertexDataId[V <: VertexDataReader](id: Long) // extends AnyVal

sealed trait VertexDataReader {
  type V <: VertexDataReader
  def id: VertexDataId[V]
  def dump: Array[Byte]
}

object VertexDataReader {
  def apply(rawDataReader: RawDataReader): Map[VertexKind, VertexDataReader] = {
    VertexKind.all.map { vertexKind => vertexKind -> vertexKind(rawDataReader) }.toMap
  }
}

trait UserReader extends VertexDataReader { type V = UserReader }
case object UserReader extends VertexKind {
  type V = UserReader
  val header = KindHeader[V](1)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait UriReader extends VertexDataReader { type V = UriReader }
case object UriReader extends VertexKind {
  type V = UriReader
  val header = KindHeader[V](2)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait TagReader extends VertexDataReader { type V = TagReader }
case object TagReader extends VertexKind {
  type V = TagReader
  val header = KindHeader[V](3)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait ThreadReader extends VertexDataReader { type V = ThreadReader }
case object ThreadReader extends VertexKind {
  type V = ThreadReader
  val header = KindHeader[V](4)
  def apply(rawDataReader: RawDataReader): V = ???
}

trait SocialAccountReader extends VertexDataReader { type V = SocialAccountReader }
case object SocialAccountReader extends VertexKind {
  type V = ThreadReader
  val header = KindHeader[V](5)
  def apply(rawDataReader: RawDataReader): V = ???
}

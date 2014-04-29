package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._


sealed trait VertexDataReader { self =>
  type V >: self.type <: VertexDataReader
  def instance: V = self
  def kind: VertexKind[V]
  def id: VertexDataId[V]
}

object VertexDataReader {
  // Binary Helpers
  def apply(rawDataReader: RawDataReader): Map[VertexKind[_ <: VertexDataReader], VertexDataReader] = {
    VertexKind.all.map { vertexKind => vertexKind -> vertexKind(rawDataReader) }.toMap
  }

  // Json Helpers
  implicit val writes: Writes[VertexDataReader] = Writes[VertexDataReader](vertexDataReader => Json.obj(
    "header" -> vertexDataReader.kind.header.toInt,
    "data" -> vertexDataReader.kind.writes.writes(vertexDataReader.instance)
  ))
  implicit val readsAsVertexData: Reads[VertexData[_ <: VertexDataReader]] = Reads(json =>
    (json \ "header").validate[Int].flatMap[VertexData[_ <: VertexDataReader]] { header =>
      VertexKind(header.toByte).readsAsVertexData.reads(json \ "data")
    }
  )
}

sealed trait VertexKind[V <: VertexDataReader] {
  implicit def kind: VertexKind[V] = this

  // Binary Helpers
  def header: Byte
  def apply(rawDataReader: RawDataReader): V

  // Json helpers
  implicit def idFormat: Format[VertexDataId[V]] = VertexDataId.format[V]
  def writes: Writes[V]
  def readsAsVertexData: Reads[VertexData[V]]
}

object VertexKind {
  val all: Set[VertexKind[_ <: VertexDataReader]] = CompanionTypeSystem[VertexDataReader, VertexKind[_ <: VertexDataReader]]("V")
  private val byHeader: Map[Byte, VertexKind[_ <: VertexDataReader]] = {
    require(all.forall(_.header > 0), "VertexKind headers must be positive.")
    require(all.size == all.map(_.header).size, "Duplicate VertexKind headers.")
    all.map { vertexKind => vertexKind.header -> vertexKind }.toMap
  }
  def apply(header: Byte): VertexKind[_ <: VertexDataReader] = byHeader(header)
}

trait UserReader extends VertexDataReader {
  type V = UserReader
  def kind = UserReader
}
case object UserReader extends VertexKind[UserReader] {
  val header = 1.toByte
  def apply(rawDataReader: RawDataReader): UserReader = ???
  implicit val writes = Writes[UserReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[UserReader]] { json => (json \ "id").validate.map(UserData(_)) }
}

trait UriReader extends VertexDataReader {
  type V = UriReader
  def kind = UriReader
}
case object UriReader extends VertexKind[UriReader] {
  val header = 2.toByte
  def apply(rawDataReader: RawDataReader): UriReader = ???
  implicit val writes = Writes[UriReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[UriReader]] { json => (json \ "id").validate.map(UriData(_)) }
}

trait TagReader extends VertexDataReader {
  type V = TagReader
  def kind = TagReader
}
case object TagReader extends VertexKind[TagReader] {
  val header = 3.toByte
  def apply(rawDataReader: RawDataReader): TagReader = ???
  implicit val writes = Writes[TagReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[TagReader]] { json => (json \ "id").validate.map(TagData(_)) }
}

trait ThreadReader extends VertexDataReader {
  type V = ThreadReader
  def kind = ThreadReader
}
case object ThreadReader extends VertexKind[ThreadReader] {
  val header = 4.toByte
  def apply(rawDataReader: RawDataReader): ThreadReader = ???
  implicit val writes = Writes[ThreadReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[ThreadReader]] { json => (json \ "id").validate.map(ThreadData(_)) }
}

trait FacebookAccountReader extends VertexDataReader {
  type V = FacebookAccountReader
  def kind = FacebookAccountReader
}
case object FacebookAccountReader extends VertexKind[FacebookAccountReader] {
  val header = 5.toByte
  def apply(rawDataReader: RawDataReader): FacebookAccountReader = ???
  implicit val writes = Writes[FacebookAccountReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[FacebookAccountReader]] { json => (json \ "id").validate.map(FacebookAccountData(_)) }
}

trait LinkedInAccountReader extends VertexDataReader {
  type V = LinkedInAccountReader
  def kind = LinkedInAccountReader
}
case object LinkedInAccountReader extends VertexKind[LinkedInAccountReader] {
  val header = 6.toByte
  def apply(rawDataReader: RawDataReader): LinkedInAccountReader = ???
  implicit val writes = Writes[LinkedInAccountReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[LinkedInAccountReader]] { json => (json \ "id").validate.map(LinkedInAccountData(_)) }
}

trait KeepReader extends VertexDataReader {
  type V = KeepReader
  def kind = KeepReader
}
case object KeepReader extends VertexKind[KeepReader] {
  val header = 7.toByte
  def apply(rawDataReader: RawDataReader): KeepReader = ???
  implicit val writes = Writes[KeepReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[KeepReader]] { json => (json \ "id").validate.map(KeepData(_)) }
}

package com.keepit.graph.model

import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda.{ LDATopic, DenseLDA }
import com.keepit.graph.manager.LDATopicId

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
  def id(id: Long): VertexDataId[V] = VertexDataId[V](id)

  // Json helpers
  val code: String = toString.stripSuffix("Reader")
  implicit val idFormat: Format[VertexDataId[V]] = VertexDataId.format[V]
  def writes: Writes[V]
  def readsAsVertexData: Reads[VertexData[V]]
}

object VertexKind {
  type VertexType = VertexKind[_ <: VertexDataReader]
  val all: Set[VertexType] = CompanionTypeSystem[VertexDataReader, VertexKind[_ <: VertexDataReader]]("V")

  private val byHeader: Map[Byte, VertexType] = {
    require(all.forall(_.header > 0), "VertexKind headers must be positive.")
    require(all.size == all.map(_.header).size, "Duplicate VertexKind headers.")
    all.map { vertexKind => vertexKind.header -> vertexKind }.toMap
  }
  def apply(header: Byte): VertexType = byHeader(header)

  private val byCode: Map[String, VertexType] = {
    require(all.size == all.map(_.code).size, "Duplicate VertexKind codes.")
    all.map { vertexKind => vertexKind.code -> vertexKind }.toMap
  }
  def apply(code: String): VertexType = byCode(code)
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

trait LDATopicReader extends VertexDataReader {
  type V = LDATopicReader
  def kind = LDATopicReader

  def version(): ModelVersion[DenseLDA] = LDATopicId.versionFromLong(id.id)
  def topic(): LDATopic = LDATopicId.topicFromLong(id.id)
}
case object LDATopicReader extends VertexKind[LDATopicReader] {
  val header = 7.toByte
  def apply(rawDataReader: RawDataReader): LDATopicReader = ???
  implicit val writes = Writes[LDATopicReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[LDATopicReader]] { json => (json \ "id").validate.map(LDATopicData(_)) }
}

trait KeepReader extends VertexDataReader {
  type V = KeepReader
  def kind = KeepReader
}
case object KeepReader extends VertexKind[KeepReader] {
  val header = 8.toByte
  def apply(rawDataReader: RawDataReader): KeepReader = ???
  implicit val writes = Writes[KeepReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[KeepReader]] { json => (json \ "id").validate.map(KeepData(_)) }
}

trait EmailAccountReader extends VertexDataReader {
  type V = EmailAccountReader
  def kind = EmailAccountReader
}
case object EmailAccountReader extends VertexKind[EmailAccountReader] {
  val header = 9.toByte
  def apply(rawDataReader: RawDataReader): EmailAccountReader = ???
  implicit val writes = Writes[EmailAccountReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[EmailAccountReader]] { json => (json \ "id").validate.map(EmailAccountData(_)) }
}

trait AddressBookReader extends VertexDataReader {
  type V = AddressBookReader
  def kind = AddressBookReader
}
case object AddressBookReader extends VertexKind[AddressBookReader] {
  val header = 10.toByte
  def apply(rawDataReader: RawDataReader): AddressBookReader = ???
  implicit val writes = Writes[AddressBookReader](reader => Json.obj("id" -> reader.id))
  implicit val readsAsVertexData = Reads[VertexData[AddressBookReader]] { json => (json \ "id").validate.map(AddressBookData(_)) }
}

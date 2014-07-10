package com.keepit.common.queue.messages

import com.keepit.common.db.Id
import com.keepit.common.db.SequenceNumber
import com.keepit.model.NormalizedURI

import play.api.libs.json.{ Reads, Json, Format, JsValue }
import com.keepit.common.reflection.CompanionTypeSystem

sealed trait CortexFeatureMessage { self =>
  type M >: self.type <: CortexFeatureMessage
  def kind: CortexFeatureMessageKind[M]
  def instance: M = self
}

sealed trait CortexFeatureMessageKind[M <: CortexFeatureMessage] {
  def typeCode: String
  def format: Format[M]
}

object CortexFeatureMessageKind {
  val all = CompanionTypeSystem[CortexFeatureMessage, CortexFeatureMessageKind[_ <: CortexFeatureMessage]]("M")
  val byTypeCode: Map[String, CortexFeatureMessageKind[_ <: CortexFeatureMessage]] = {
    require(all.size == all.map(_.typeCode).size, "Duplicate CortexFeatureMessage type codes.")
    all.map { msgKind => msgKind.typeCode -> msgKind }.toMap
  }
}

object CortexFeatureMessage {
  implicit val format = new Format[CortexFeatureMessage] {
    def writes(msg: CortexFeatureMessage) = Json.obj(
      "typeCode" -> msg.kind.typeCode.toString(),
      "value" -> msg.kind.format.writes(msg.instance)
    )
    def reads(json: JsValue) = (json \ "typeCode").validate[String].flatMap { typeCode =>
      CortexFeatureMessageKind.byTypeCode(typeCode).format.reads(json \ "value")
    }
  }
}

case class DenseLDAURIFeatureMessage(
    id: Id[NormalizedURI],
    seq: SequenceNumber[NormalizedURI],
    modelName: String,
    modelVersion: Int,
    feature: Array[Float]) extends CortexFeatureMessage {
  type M = DenseLDAURIFeatureMessage
  def kind = DenseLDAURIFeatureMessage
}

case object DenseLDAURIFeatureMessage extends CortexFeatureMessageKind[DenseLDAURIFeatureMessage] {
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val seqFormat = SequenceNumber.format[NormalizedURI]
  implicit val typeCode = "dense_lda_uri_feature"
  implicit val format = Json.format[DenseLDAURIFeatureMessage]
}

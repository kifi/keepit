package com.keepit.graph.ingestion

import com.keepit.common.db.SequenceNumber
import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json.{JsValue, Json, Format}

sealed trait GraphUpdate { self =>
  type U >: self.type <: GraphUpdate
  def seq: SequenceNumber[U]
  def kind: GraphUpdateKind[U]
  def instance: U = self
}

object GraphUpdate {
  implicit val format = new Format[GraphUpdate] {
    def writes(update: GraphUpdate) = Json.obj("code" -> update.kind.code, "value" -> update.kind.format.writes(update.instance))
    def reads(json: JsValue) = (json \ "code").validate[String].flatMap { code => GraphUpdateKind(code).format.reads(json \ "value") }
  }
}

sealed trait GraphUpdateKind[U <: GraphUpdate] {
  def code: String
  def format: Format[U]
  def seq(value: Long): SequenceNumber[U] = SequenceNumber(value)
}

object GraphUpdateKind {
  val all: Set[GraphUpdateKind[_ <: GraphUpdate]] = CompanionTypeSystem[GraphUpdate, GraphUpdateKind[_ <: GraphUpdate]]("I")
  private val byCode: Map[String, GraphUpdateKind[_ <: GraphUpdate]] = {
    require(all.size == all.map(_.code).size, "Duplicate GraphUpdateKind names.")
    all.map { ingestableKind => ingestableKind.code -> ingestableKind }.toMap
  }
  def apply(code: String): GraphUpdateKind[_ <: GraphUpdate] = byCode(code)
}

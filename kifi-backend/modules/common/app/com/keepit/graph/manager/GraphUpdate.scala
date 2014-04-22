package com.keepit.graph.manager

import com.keepit.common.db.{Id, SequenceNumber}
import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._
import com.keepit.model.User
import com.keepit.common.time.DateTimeJsonFormat
import play.api.libs.functional.syntax._

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
  val all: Set[GraphUpdateKind[_ <: GraphUpdate]] = CompanionTypeSystem[GraphUpdate, GraphUpdateKind[_ <: GraphUpdate]]("U")
  private val byCode: Map[String, GraphUpdateKind[_ <: GraphUpdate]] = {
    require(all.size == all.map(_.code).size, "Duplicate GraphUpdateKind names.")
    all.map { ingestableKind => ingestableKind.code -> ingestableKind }.toMap
  }
  def apply(code: String): GraphUpdateKind[_ <: GraphUpdate] = byCode(code)
}

case class UserGraphUpdate(userId: Id[User], userSeq: SequenceNumber[User]) extends GraphUpdate {
  type U = UserGraphUpdate
  def kind = UserGraphUpdate
  def seq = kind.seq(userSeq.value)
}

case object UserGraphUpdate extends GraphUpdateKind[UserGraphUpdate] {
  val code = "user_graph_update"
  implicit val format: Format[UserGraphUpdate] = (
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'userSeq).format(SequenceNumber.format[User])
  )(UserGraphUpdate.apply, unlift(UserGraphUpdate.unapply))
}

package com.keepit.graph.manager

import com.keepit.common.db.{State, Id, SequenceNumber}
import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._
import com.keepit.model._
import com.keepit.common.time.DateTimeJsonFormat
import play.api.libs.functional.syntax._
import com.keepit.social.SocialNetworkType
import com.keepit.cortex.VersionedSequenceNumber

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

case class UserConnectionGraphUpdate(firstUserId: Id[User], secondUserId: Id[User], state: State[UserConnection], userConnectionSeq: SequenceNumber[UserConnection]) extends GraphUpdate {
  type U = UserConnectionGraphUpdate
  def kind = UserConnectionGraphUpdate
  def seq = kind.seq(userConnectionSeq.value)
}

case object UserConnectionGraphUpdate extends GraphUpdateKind[UserConnectionGraphUpdate] {
  val code = "user_connection_graph_update"
  implicit val format: Format[UserConnectionGraphUpdate] = (
    (__ \ 'firstUserId).format(Id.format[User]) and
    (__ \ 'secondUserId).format(Id.format[User]) and
    (__ \ 'state).format(State.format[UserConnection]) and
    (__ \ 'userConnectionSeq).format(SequenceNumber.format[UserConnection])
  )(UserConnectionGraphUpdate.apply, unlift(UserConnectionGraphUpdate.unapply))
}

case class SocialUserInfoGraphUpdate(socialUserId: Id[SocialUserInfo], network: SocialNetworkType, userId: Option[Id[User]], socialUserSeq: SequenceNumber[SocialUserInfo]) extends GraphUpdate {
  type U = SocialUserInfoGraphUpdate
  def kind = SocialUserInfoGraphUpdate
  def seq = kind.seq(socialUserSeq.value)
}

case object SocialUserInfoGraphUpdate extends GraphUpdateKind[SocialUserInfoGraphUpdate] {
  val code = "social_user_info_graph_update"
  implicit val format: Format[SocialUserInfoGraphUpdate] = (
    (__ \ 'socialUserId).format(Id.format[SocialUserInfo]) and
    (__ \ 'network).format[SocialNetworkType] and
    (__ \ 'userId).formatNullable(Id.format[User]) and
    (__ \ 'socialUserSeq).format(SequenceNumber.format[SocialUserInfo])
  )(SocialUserInfoGraphUpdate.apply, unlift(SocialUserInfoGraphUpdate.unapply))
}

case class SocialConnectionGraphUpdate(firstSocialUserId: Id[SocialUserInfo], secondSocialUserId: Id[SocialUserInfo], network: SocialNetworkType, state: State[SocialConnection], socialConnectionSeq: SequenceNumber[SocialConnection]) extends GraphUpdate {
  type U = SocialConnectionGraphUpdate
  def kind = SocialConnectionGraphUpdate
  def seq = kind.seq(socialConnectionSeq.value)
}

case object SocialConnectionGraphUpdate extends GraphUpdateKind[SocialConnectionGraphUpdate] {
  val code = "social_connection_graph_update"
  implicit val format: Format[SocialConnectionGraphUpdate] = (
    (__ \ 'firstSocialUserId).format(Id.format[SocialUserInfo]) and
    (__ \ 'secondSocialUserId).format(Id.format[SocialUserInfo]) and
    (__ \ 'network).format[SocialNetworkType] and
    (__ \ 'state).format(State.format[SocialConnection]) and
    (__ \ 'SocialConnectionSeq).format(SequenceNumber.format[SocialConnection])
  )(SocialConnectionGraphUpdate.apply, unlift(SocialConnectionGraphUpdate.unapply))
}

case class LDAURITopicGraphUpdate(
  uriId: Id[NormalizedURI],
  uriSeq: SequenceNumber[NormalizedURI],
  modelName: String,
  modelVersion: Int,
  topics: Array[Float]
) extends GraphUpdate {
  type U = LDAURITopicGraphUpdate
  def kind = LDAURITopicGraphUpdate
  def seq = kind.seq(VersionedSequenceNumber.toLong(VersionedSequenceNumber(modelVersion, uriSeq.value)))
}

case object LDAURITopicGraphUpdate extends GraphUpdateKind[LDAURITopicGraphUpdate]{
  val code = "lda_uri_topic_graph_update"
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val seqFormat = SequenceNumber.format[NormalizedURI]
  implicit val format = Json.format[LDAURITopicGraphUpdate]
}

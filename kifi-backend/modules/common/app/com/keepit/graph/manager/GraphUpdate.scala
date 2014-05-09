package com.keepit.graph.manager

import com.keepit.common.db.{State, Id, SequenceNumber}
import com.keepit.common.reflection.CompanionTypeSystem
import play.api.libs.json._
import com.keepit.model._
import com.keepit.common.time.DateTimeJsonFormat
import play.api.libs.functional.syntax._
import com.keepit.social.SocialNetworkType
import com.keepit.cortex.CortexVersionedSequenceNumber
import com.keepit.cortex.models.lda.SparseTopicRepresentation

sealed trait GraphUpdate { self =>
  type U >: self.type <: GraphUpdate
  def seq: SequenceNumber[U]
  def kind: GraphUpdateKind[U]
  def instance: U = self
}

sealed trait GraphUpdateKind[U <: GraphUpdate] {
  def code: String
  def seq(value: Long): SequenceNumber[U] = SequenceNumber(value)
}

object GraphUpdateKind {
  val all: Set[GraphUpdateKind[_ <: GraphUpdate]] = CompanionTypeSystem[GraphUpdate, GraphUpdateKind[_ <: GraphUpdate]]("U")
  private val byCode: Map[String, GraphUpdateKind[_ <: GraphUpdate]] = {
    require(all.size == all.map(_.code).size, "Duplicate GraphUpdateKind names.")
    all.map { kind => kind.code -> kind }.toMap
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
  def apply(user: User): UserGraphUpdate = UserGraphUpdate(user.id.get, user.seq)
}

case class UserConnectionGraphUpdate(firstUserId: Id[User], secondUserId: Id[User], state: State[UserConnection], userConnectionSeq: SequenceNumber[UserConnection]) extends GraphUpdate {
  type U = UserConnectionGraphUpdate
  def kind = UserConnectionGraphUpdate
  def seq = kind.seq(userConnectionSeq.value)
}

case object UserConnectionGraphUpdate extends GraphUpdateKind[UserConnectionGraphUpdate] {
  val code = "user_connection_graph_update"
  def apply(userConnection: UserConnection): UserConnectionGraphUpdate = UserConnectionGraphUpdate(userConnection.user1, userConnection.user2, userConnection.state, userConnection.seq)
}

case class SocialUserInfoGraphUpdate(socialUserId: Id[SocialUserInfo], network: SocialNetworkType, userId: Option[Id[User]], socialUserSeq: SequenceNumber[SocialUserInfo]) extends GraphUpdate {
  type U = SocialUserInfoGraphUpdate
  def kind = SocialUserInfoGraphUpdate
  def seq = kind.seq(socialUserSeq.value)
}

case object SocialUserInfoGraphUpdate extends GraphUpdateKind[SocialUserInfoGraphUpdate] {
  val code = "social_user_info_graph_update"
}

case class SocialConnectionGraphUpdate(firstSocialUserId: Id[SocialUserInfo], secondSocialUserId: Id[SocialUserInfo], network: SocialNetworkType, state: State[SocialConnection], socialConnectionSeq: SequenceNumber[SocialConnection]) extends GraphUpdate {
  type U = SocialConnectionGraphUpdate
  def kind = SocialConnectionGraphUpdate
  def seq = kind.seq(socialConnectionSeq.value)
}

case object SocialConnectionGraphUpdate extends GraphUpdateKind[SocialConnectionGraphUpdate] {
  val code = "social_connection_graph_update"
}

case class KeepGraphUpdate(id: Id[Keep], userId: Id[User], uriId: Id[NormalizedURI], state: State[Keep], keepSeq: SequenceNumber[Keep]) extends GraphUpdate {
  type U = KeepGraphUpdate
  def kind = KeepGraphUpdate
  def seq = kind.seq(keepSeq.value)
}

case object KeepGraphUpdate extends GraphUpdateKind[KeepGraphUpdate] {
  val code = "keep_graph_update"
  def apply(keep: Keep): KeepGraphUpdate = KeepGraphUpdate(keep.id.get, keep.userId, keep.uriId, keep.state, keep.seq)
}

case class LDAURITopicGraphUpdate(
  uriId: Id[NormalizedURI],
  uriSeq: CortexVersionedSequenceNumber[NormalizedURI],
  modelName: String,
  sparseTopics: SparseTopicRepresentation
) extends GraphUpdate {
  type U = LDAURITopicGraphUpdate
  def kind = LDAURITopicGraphUpdate
  def seq = kind.seq(uriSeq.versionedSeq)
}

case object LDAURITopicGraphUpdate extends GraphUpdateKind[LDAURITopicGraphUpdate]{
  val code = "lda_uri_topic_graph_update"
  private implicit val uriIdFormat = Id.format[NormalizedURI]
  private implicit val seqFormat = CortexVersionedSequenceNumber.format[NormalizedURI]
  implicit val format = Json.format[LDAURITopicGraphUpdate]
}

package com.keepit.common.queue

import com.keepit.model.{ User, SocialUserInfo }
import com.keepit.common.db.Id
import com.keepit.social.SocialNetworkType

import play.api.libs.json.{ Reads, Json, Format, JsValue }
import com.keepit.common.reflection.CompanionTypeSystem
import com.keepit.common.mail.EmailAddress

sealed trait RichConnectionUpdateMessage { self =>
  type M >: self.type <: RichConnectionUpdateMessage
  def kind: RichConnectionUpdateMessageKind[M]
  def instance: M = self
}

sealed trait RichConnectionUpdateMessageKind[M <: RichConnectionUpdateMessage] {
  def typeCode: String
  def format: Format[M]
}

object RichConnectionUpdateMessageKind {
  val all = CompanionTypeSystem[RichConnectionUpdateMessage, RichConnectionUpdateMessageKind[_ <: RichConnectionUpdateMessage]]("M")
  val byTypeCode: Map[String, RichConnectionUpdateMessageKind[_ <: RichConnectionUpdateMessage]] = {
    require(all.size == all.map(_.typeCode).size, "Duplicate RichConnectionUpdateMessage type codes.")
    all.map { vertexKind => vertexKind.typeCode -> vertexKind }.toMap
  }
}

object RichConnectionUpdateMessage {
  implicit val format = new Format[RichConnectionUpdateMessage] {
    def writes(message: RichConnectionUpdateMessage) = Json.obj("typeCode" -> message.kind.typeCode.toString, "value" -> message.kind.format.writes(message.instance))
    def reads(json: JsValue) = (json \ "typeCode").validate[String].flatMap { typeCode => RichConnectionUpdateMessageKind.byTypeCode(typeCode).format.reads(json \ "value") }
  }
}

//Propages changes to SocialConnectionRepo (needs sequence number). Will usually be a queued call.
case class InternRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) extends RichConnectionUpdateMessage {
  type M = InternRichConnection
  def kind = InternRichConnection
}
case object InternRichConnection extends RichConnectionUpdateMessageKind[InternRichConnection] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[InternRichConnection]
  implicit val typeCode = "intern_rich_connection"
}

case class RemoveRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) extends RichConnectionUpdateMessage {
  type M = RemoveRichConnection
  def kind = RemoveRichConnection
}
case object RemoveRichConnection extends RichConnectionUpdateMessageKind[RemoveRichConnection] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RemoveRichConnection]
  implicit val typeCode = "remove_rich_connection"
}

//Propages changes to UserConnectionRepo. Will usually be a queued call.
case class RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) extends RichConnectionUpdateMessage {
  type M = RecordKifiConnection
  def kind = RecordKifiConnection
}
case object RecordKifiConnection extends RichConnectionUpdateMessageKind[RecordKifiConnection] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RecordKifiConnection]
  implicit val typeCode = "record_kifi_connection"
}

case class RemoveKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) extends RichConnectionUpdateMessage {
  type M = RemoveKifiConnection
  def kind = RemoveKifiConnection
}
case object RemoveKifiConnection extends RichConnectionUpdateMessageKind[RemoveKifiConnection] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RemoveKifiConnection]
  implicit val typeCode = "remove_kifi_connection"
}

//Propages changes to InvitationRepo (needs sequence number).
case class RecordInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEmailAddress: Option[EmailAddress], invitationNumber: Int = 1) extends RichConnectionUpdateMessage {
  type M = RecordInvitation
  def kind = RecordInvitation
}
case object RecordInvitation extends RichConnectionUpdateMessageKind[RecordInvitation] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RecordInvitation]
  implicit val typeCode = "record_inivitation"
}

//Propages changes to InvitationRepo (needs sequence number).
case class CancelInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEmailAddress: Option[EmailAddress]) extends RichConnectionUpdateMessage {
  type M = CancelInvitation
  def kind = CancelInvitation
}
case object CancelInvitation extends RichConnectionUpdateMessageKind[CancelInvitation] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[CancelInvitation]
  implicit val typeCode = "cancel_invitation"
}

//Propages changes to SocialUserInfoRepo (needs sequence number). Will usually be a direct call.
case class RecordFriendUserId(networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[EmailAddress], friendUserId: Id[User]) extends RichConnectionUpdateMessage {
  type M = RecordFriendUserId
  def kind = RecordFriendUserId
}
case object RecordFriendUserId extends RichConnectionUpdateMessageKind[RecordFriendUserId] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RecordFriendUserId]
  implicit val typeCode = "record_friend_user_id"
}

//Caused by direct user action. Will usually be a direcrt call.
case class Block(userId: Id[User], networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[EmailAddress]) extends RichConnectionUpdateMessage {
  type M = Block
  def kind = Block
}
case object Block extends RichConnectionUpdateMessageKind[Block] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[Block]
  implicit val typeCode = "block"
}

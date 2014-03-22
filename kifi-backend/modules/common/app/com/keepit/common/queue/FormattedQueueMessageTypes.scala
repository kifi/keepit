package com.keepit.common.queue


import com.keepit.model.{EContact, User, SocialUserInfo, Invitation}
import com.keepit.common.db.Id
import com.keepit.serializer.{Companion, TypeCode}
import com.keepit.social.SocialNetworkType

import play.api.libs.json.{Json, Format, JsValue}


trait RichConnectionUpdateMessage

object RichConnectionUpdateMessage {
  private val typeCodeMap = TypeCode.typeCodeMap[RichConnectionUpdateMessage](
    InternRichConnection.typeCode, RecordKifiConnection.typeCode, RecordInvitation.typeCode, RecordFriendUserId.typeCode, Block.typeCode, RecordVerifiedEmail.typeCode
  )
  def getTypeCode(code: String) = typeCodeMap(code.toLowerCase)

  implicit val format = new Format[RichConnectionUpdateMessage] {
    def writes(event: RichConnectionUpdateMessage) = event match {
      case e: InternRichConnection => Companion.writes(e)
      case e: RemoveRichConnection => Companion.writes(e)
      case e: RecordKifiConnection => Companion.writes(e)
      case e: RemoveKifiConnection => Companion.writes(e)
      case e: RecordInvitation => Companion.writes(e)
      case e: CancelInvitation => Companion.writes(e)
      case e: RecordFriendUserId => Companion.writes(e)
      case e: Block => Companion.writes(e)
      case e: RecordVerifiedEmail => Companion.writes(e)
    }
    private val readsFunc = Companion.reads(InternRichConnection, RemoveRichConnection, RecordKifiConnection, RemoveKifiConnection, RecordInvitation, RecordFriendUserId, Block, RecordVerifiedEmail)
    def reads(json: JsValue) = readsFunc(json)
  }
}

//Propages changes to SocialConnectionRepo (needs sequence number). Will usually be a queued call.
case class InternRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) extends RichConnectionUpdateMessage
object InternRichConnection extends Companion[InternRichConnection] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[InternRichConnection]
  implicit val typeCode = TypeCode("intern_rich_connection")
}

case class RemoveRichConnection(user1: SocialUserInfo, user2: SocialUserInfo) extends RichConnectionUpdateMessage
object RemoveRichConnection extends Companion[RemoveRichConnection] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RemoveRichConnection]
  implicit val typeCode = TypeCode("remove_rich_connection")
}

//Propages changes to UserConnectionRepo. Will usually be a queued call.
case class RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) extends RichConnectionUpdateMessage
object RecordKifiConnection extends Companion[RecordKifiConnection] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RecordKifiConnection]
  implicit val typeCode = TypeCode("record_kifi_connection")
}


case class RemoveKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) extends RichConnectionUpdateMessage
object RemoveKifiConnection extends Companion[RemoveKifiConnection] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RemoveKifiConnection]
  implicit val typeCode = TypeCode("remove_kifi_connection")
}

//Propages changes to InvitationRepo (needs sequence number).
case class RecordInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEContact: Option[Id[EContact]], sent: Int = 1) extends RichConnectionUpdateMessage
object RecordInvitation extends Companion[RecordInvitation] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  private implicit val eContactIdFormat = Id.format[EContact]
  implicit val format = Json.format[RecordInvitation]
  implicit val typeCode = TypeCode("record_inivitation")
}

//Propages changes to InvitationRepo (needs sequence number).
case class CancelInvitation(userId: Id[User], friendSocialId: Option[Id[SocialUserInfo]], friendEContact: Option[Id[EContact]]) extends RichConnectionUpdateMessage
object CancelInvitation extends Companion[CancelInvitation] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  private implicit val eContactIdFormat = Id.format[EContact]
  implicit val format = Json.format[CancelInvitation]
  implicit val typeCode = TypeCode("cancel_invitation")
}

//Propages changes to SocialUserInfoRepo (needs sequence number). Will usually be a direct call.
case class RecordFriendUserId(networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String], friendUserId: Id[User]) extends RichConnectionUpdateMessage
object RecordFriendUserId extends Companion[RecordFriendUserId] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RecordFriendUserId]
  implicit val typeCode = TypeCode("record_friend_user_id")
}


//Caused by direct user action. Will usually be a direcrt call.
case class Block(userId: Id[User], networkType: SocialNetworkType, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) extends RichConnectionUpdateMessage
object Block extends Companion[Block] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[Block]
  implicit val typeCode = TypeCode("block")
}

//Propages changes to EmailAddressRepo (needs sequence number).
case class RecordVerifiedEmail(userId: Id[User], email: String) extends RichConnectionUpdateMessage
object RecordVerifiedEmail extends Companion[RecordVerifiedEmail] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RecordVerifiedEmail]
  implicit val typeCode = TypeCode("record_email_address")
}




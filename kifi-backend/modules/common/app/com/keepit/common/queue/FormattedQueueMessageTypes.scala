package com.keepit.common.queue


import com.keepit.model.{User, SocialUserInfo, Invitation}
import com.keepit.common.db.Id
import com.keepit.serializer.{Companion, TypeCode}
import com.keepit.social.SocialNetworkType

import play.api.libs.json.{Json, Format, JsValue}


trait RichConnectionUpdateMessage

object RichConnectionUpdateMessage {
  private val typeCodeMap = TypeCode.typeCodeMap[RichConnectionUpdateMessage](
    CreateRichConnection.typeCode, RecordKifiConnection.typeCode, RecordInvitation.typeCode, RecordFriendUserId.typeCode, Block.typeCode
  )
  def getTypeCode(code: String) = typeCodeMap(code.toLowerCase)

  implicit val format = new Format[RichConnectionUpdateMessage] {
    def writes(event: RichConnectionUpdateMessage) = event match {
      case e: CreateRichConnection => Companion.writes(e)
      case e: RecordKifiConnection => Companion.writes(e)
      case e: RecordInvitation => Companion.writes(e)
      case e: RecordFriendUserId => Companion.writes(e)
      case e: Block => Companion.writes(e)
    }
    private val readsFunc = Companion.reads(CreateRichConnection, RecordKifiConnection, RecordInvitation, RecordFriendUserId, Block)
    def reads(json: JsValue) = readsFunc(json)
  }
}

//Propages changes to SocialConnectionRepo (needs sequence number). Will usually be a queued call.
case class CreateRichConnection(userId: Id[User], userSocialId: Id[SocialUserInfo], friend: SocialUserInfo) extends RichConnectionUpdateMessage
object CreateRichConnection extends Companion[CreateRichConnection] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[CreateRichConnection]
  implicit val typeCode = TypeCode("create_rich_connection")
}

//Propages changes to UserConnectionRepo. Will usually be a queued call.
case class RecordKifiConnection(firstUserId: Id[User], secondUserId: Id[User]) extends RichConnectionUpdateMessage
object RecordKifiConnection extends Companion[RecordKifiConnection] {
  private implicit val userIdFormat = Id.format[User]
  implicit val format = Json.format[RecordKifiConnection]
  implicit val typeCode = TypeCode("record_kifi_connection")
}


//Propages changes to InvitationRepo (needs sequence number).
case class RecordInvitation(userId: Id[User], invitation: Id[Invitation], networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) extends RichConnectionUpdateMessage
object RecordInvitation extends Companion[RecordInvitation] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val invitationIdFormat = Id.format[Invitation]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RecordInvitation]
  implicit val typeCode = TypeCode("record_inivitation")
}


//Propages changes to SocialUserInfoRepo (needs sequence number). Will usually be a direct call.
case class RecordFriendUserId(networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String], friendUserId: Id[User]) extends RichConnectionUpdateMessage
object RecordFriendUserId extends Companion[RecordFriendUserId] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[RecordFriendUserId]
  implicit val typeCode = TypeCode("record_friend_user_id")
}


//Caused by direct user action. Will usually be a direcrt call.
case class Block(userId: Id[User], networkType: String, friendSocialId: Option[Id[SocialUserInfo]], friendEmail: Option[String]) extends RichConnectionUpdateMessage
object Block extends Companion[Block] {
  private implicit val userIdFormat = Id.format[User]
  private implicit val socialIdFormat = Id.format[SocialUserInfo]
  implicit val format = Json.format[Block]
  implicit val typeCode = TypeCode("block")
}




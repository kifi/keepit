package com.keepit.model

import com.keepit.common.db.ExternalId
import com.keepit.social.{ BasicUser, BasicUserFields }

import org.joda.time.DateTime

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class BasicUserWithFriendStatus(
  externalId: ExternalId[User],
  firstName: String,
  lastName: String,
  pictureName: String,
  username: Username,
  isFriend: Option[Boolean],
  friendRequestSentAt: Option[DateTime],
  friendRequestReceivedAt: Option[DateTime]) extends BasicUserFields

object BasicUserWithFriendStatus {
  implicit val format = (
    BasicUserFields.format and
    (__ \ 'isFriend).formatNullable[Boolean] and
    (__ \ 'friendRequestSentAt).formatNullable[DateTime] and
    (__ \ 'friendRequestReceivedAt).formatNullable[DateTime]
  )(BasicUserWithFriendStatus.apply, unlift(BasicUserWithFriendStatus.unapply))

  def fromWithoutFriendStatus(u: User): BasicUserWithFriendStatus = fromWithoutFriendStatus(BasicUser.fromUser(u))
  def fromWithoutFriendStatus(u: BasicUser): BasicUserWithFriendStatus = from(u, None, None, None)
  def from(u: User, isFriend: Boolean): BasicUserWithFriendStatus = from(BasicUser.fromUser(u), isFriend)
  def from(u: BasicUser, isFriend: Boolean): BasicUserWithFriendStatus = from(u, Some(isFriend))
  def fromWithRequestSentAt(u: User, at: DateTime): BasicUserWithFriendStatus = fromWithRequestSentAt(BasicUser.fromUser(u), at)
  def fromWithRequestSentAt(u: BasicUser, at: DateTime): BasicUserWithFriendStatus = from(u, Some(false), friendRequestSentAt = Some(at))
  def fromWithRequestReceivedAt(u: User, at: DateTime): BasicUserWithFriendStatus = fromWithRequestReceivedAt(BasicUser.fromUser(u), at)
  def fromWithRequestReceivedAt(u: BasicUser, at: DateTime): BasicUserWithFriendStatus = from(u, Some(false), friendRequestReceivedAt = Some(at))
  private def from(u: BasicUser, isFriend: Option[Boolean], friendRequestSentAt: Option[DateTime] = None, friendRequestReceivedAt: Option[DateTime] = None): BasicUserWithFriendStatus = {
    BasicUserWithFriendStatus(
      externalId = u.externalId,
      firstName = u.firstName,
      lastName = u.lastName,
      pictureName = u.pictureName,
      username = u.username,
      isFriend = isFriend,
      friendRequestSentAt = friendRequestSentAt,
      friendRequestReceivedAt = friendRequestReceivedAt)
  }
}

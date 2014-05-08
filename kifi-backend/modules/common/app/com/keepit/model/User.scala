package com.keepit.model

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class User(
  id: Option[Id[User]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  externalId: ExternalId[User] = ExternalId(),
  firstName: String,
  lastName: String,
  state: State[User] = UserStates.ACTIVE,
  pictureName: Option[String] = None, // denormalized UserPicture.name
  userPictureId: Option[Id[UserPicture]] = None,
  seq: SequenceNumber[User] = SequenceNumber.ZERO,
  primaryEmailId: Option[Id[EmailAddress]] = None
) extends ModelWithExternalId[User] with ModelWithState[User] with ModelWithSeqNumber[User]{
  def withId(id: Id[User]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withName(firstName: String, lastName: String) = copy(firstName = firstName, lastName = lastName)
  def withExternalId(id: ExternalId[User]) = copy(externalId = id)
  def withState(state: State[User]) = copy(state = state)
  def fullName = s"$firstName $lastName"
  def shortName = if (firstName.length>0) firstName else lastName
  override def toString(): String = s"""User[id=$id,externalId=$externalId,name="$firstName $lastName",state=$state]"""
}

object User {
  implicit val userPicIdFormat = Id.format[UserPicture]
  implicit val emailAddressIdFormat = Id.format[EmailAddress]
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[User]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'externalId).format(ExternalId.format[User]) and
    (__ \ 'firstName).format[String] and
    (__ \ 'lastName).format[String] and
    (__ \ 'state).format(State.format[User]) and
    (__ \ 'pictureName).formatNullable[String] and
    (__ \ 'userPictureId).formatNullable[Id[UserPicture]] and
    (__ \ 'seq).format(SequenceNumber.format[User]) and
    (__ \ 'primaryEmailId).formatNullable[Id[EmailAddress]]
  )(User.apply, unlift(User.unapply))

  val brackets = "[<>]".r
  def sanitizeName(str: String) = brackets.replaceAllIn(str, "")
}

case class UserExternalIdKey(externalId: ExternalId[User]) extends Key[User] {
  override val version = 6
  val namespace = "user_by_external_id"
  def toKey(): String = externalId.id
}

class UserExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserExternalIdKey, User](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class UserIdKey(id: Id[User]) extends Key[User] {
  override val version = 7
  val namespace = "user_by_id"
  def toKey(): String = id.id.toString
}

class UserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserIdKey, User](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)

case class ExternalUserIdKey(id: ExternalId[User]) extends Key[Id[User]] {
  override val version = 5
  val namespace = "user_id_by_external_id"
  def toKey(): String = id.id.toString
}

class ExternalUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ExternalUserIdKey, Id[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings:_*)(Id.format[User])

object UserStates extends States[User] {
  val PENDING = State[User]("pending")
  val BLOCKED = State[User]("blocked")
  val INCOMPLETE_SIGNUP = State[User]("incomplete_signup")
}


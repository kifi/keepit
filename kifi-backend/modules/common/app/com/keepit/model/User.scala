package com.keepit.model

import java.util.UUID
import java.net.URLEncoder

import play.api.mvc.{ PathBindable, QueryStringBindable }

import scala.concurrent.duration._

import org.joda.time.DateTime

import com.kifi.macros.json
import com.keepit.common.cache._
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.strings.UTF8
import com.keepit.common.time._

import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.common.mail.EmailAddress

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
    primaryEmail: Option[EmailAddress] = None,
    username: Username,
    normalizedUsername: String) extends ModelWithExternalId[User] with ModelWithState[User] with ModelWithSeqNumber[User] {
  def withId(id: Id[User]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withName(firstName: String, lastName: String) = copy(firstName = firstName, lastName = lastName)
  def withExternalId(id: ExternalId[User]) = copy(externalId = id)
  def withState(state: State[User]) = copy(state = state)
  def fullName = s"$firstName $lastName"
  def shortName = if (firstName.length > 0) firstName else lastName
  override def toString(): String = s"""User[id=$id,externalId=$externalId,name="$firstName $lastName",username=$username, normalizedUsername=$normalizedUsername, state=$state]"""
}

object User {
  implicit val userPicIdFormat = Id.format[UserPicture]
  implicit val usernameFormat = Username.jsonAnnotationFormat

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
    (__ \ 'primaryEmail).formatNullable[EmailAddress] and
    (__ \ 'username).format[Username] and
    (__ \ 'normalizedUsername).format[String]
  )(User.apply, unlift(User.unapply))

  val brackets = "[<>]".r
  def sanitizeName(str: String) = brackets.replaceAllIn(str, "")
}

@json
case class Username(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object Username {

  implicit def queryStringBinder(implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[Username] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, Username]] = {
      stringBinder.bind(key, params) map {
        case Right(username) => Right(Username(username))
        case _ => Left("Unable to bind an Username")
      }
    }

    override def unbind(key: String, id: Username): String = {
      stringBinder.unbind(key, id.value)
    }
  }

  implicit def pathBinder[T] = new PathBindable[Username] {
    override def bind(key: String, value: String): Either[String, Username] = Right(Username(value))

    override def unbind(key: String, username: Username): String = username.value
  }
}

case class UserExternalIdKey(externalId: ExternalId[User]) extends Key[User] {
  override val version = 8
  val namespace = "user_by_external_id"
  def toKey(): String = externalId.id
}

case class UsernameKey(username: Username) extends Key[User] {
  override val version = 1
  val namespace = "username"
  def toKey(): String = username.value
}

class UsernameCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends ImmutableJsonCacheImpl[UsernameKey, User](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

class UserExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserExternalIdKey, User](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class UserIdKey(id: Id[User]) extends Key[User] {
  override val version = 9
  val namespace = "user_by_id"
  def toKey(): String = id.id.toString
}

class UserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserIdKey, User](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

case class ExternalUserIdKey(id: ExternalId[User]) extends Key[Id[User]] {
  override val version = 6
  val namespace = "user_id_by_external_id"
  def toKey(): String = id.id.toString
}

class ExternalUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[ExternalUserIdKey, Id[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Id.format[User])

case class UserImageUrlCacheKey(userId: Id[User], width: Int, imageName: String) extends Key[String] {
  override val version = 1
  val namespace = "user_image_by_width"
  def toKey(): String = s"$userId#$width#$imageName"
}

class UserImageUrlCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends StringCacheImpl[UserImageUrlCacheKey](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object UserStates extends States[User] {
  val PENDING = State[User]("pending")
  val BLOCKED = State[User]("blocked")
  val INCOMPLETE_SIGNUP = State[User]("incomplete_signup")
}

case class VerifiedEmailUserIdKey(address: EmailAddress) extends Key[Id[User]] {
  override val version = 2
  val namespace = "user_id_by_verified_email"
  def toKey(): String = address.address
}

class VerifiedEmailUserIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[VerifiedEmailUserIdKey, Id[User]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)(Id.format[User])

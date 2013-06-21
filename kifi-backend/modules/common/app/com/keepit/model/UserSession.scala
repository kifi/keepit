package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.db._
import com.keepit.common.social.{SocialNetworkType, SocialId}
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.duration._

case class UserSession(
  id: Option[Id[UserSession]] = None,
  userId: Option[Id[User]] = None,
  externalId: ExternalId[UserSession],
  socialId: SocialId,
  provider: SocialNetworkType,
  expires: DateTime,
  state: State[UserSession] = UserSessionStates.ACTIVE,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime) extends ModelWithExternalId[UserSession] {
  def withId(id: Id[UserSession]) = copy(id = Some(id))
  def withUpdateTime(now: DateTime) = copy(updatedAt = now)
  def isValid = state == UserSessionStates.ACTIVE && expires.isAfterNow
  def invalidated = copy(state = UserSessionStates.INACTIVE)
}

object UserSession {
  private implicit val idFormat = Id.format[UserSession]
  private implicit val userIdFormat = Id.format[User]
  private implicit val externalIdFormat = ExternalId.format[UserSession]
  private implicit val stateFormat = State.format[UserSession]

  implicit val userSessionFormat: Format[UserSession] = (
    (__ \ 'id).formatNullable[Id[UserSession]] and
    (__ \ 'userId).formatNullable[Id[User]] and
    (__ \ 'externalId).format[ExternalId[UserSession]] and
    (__ \ 'socialId).format[String].inmap(SocialId.apply, unlift(SocialId.unapply)) and
    (__ \ 'provider).format[String].inmap(SocialNetworkType.apply, unlift(SocialNetworkType.unapply)) and
    (__ \ 'expires).format[DateTime] and
    (__ \ 'state).format[State[UserSession]] and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime]
  )(UserSession.apply, unlift(UserSession.unapply))
}

case class UserSessionExternalIdKey(externalId: ExternalId[UserSession]) extends Key[UserSession] {
  override val version = 2
  val namespace = "user_session_by_external_id"
  def toKey(): String = externalId.id
}

class UserSessionExternalIdCache(innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
    extends JsonCacheImpl[UserSessionExternalIdKey, UserSession](innermostPluginSettings, innerToOuterPluginSettings:_*)

object UserSessionStates extends States[UserSession]

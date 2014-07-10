package com.keepit.model

import com.keepit.common.cache._
import com.keepit.common.cache.CacheStatistics
import com.keepit.common.logging.AccessLog
import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.duration._
import com.keepit.social.{ SocialNetworkType, SocialId }

case class UserSession(
    id: Option[Id[UserSession]] = None,
    userId: Option[Id[User]] = None,
    externalId: ExternalId[UserSession],
    socialId: SocialId,
    provider: SocialNetworkType,
    expires: DateTime,
    state: State[UserSession] = UserSessionStates.ACTIVE,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime) extends ModelWithExternalId[UserSession] with ModelWithState[UserSession] {
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
    (__ \ 'expires).format(DateTimeJsonFormat) and
    (__ \ 'state).format[State[UserSession]] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat)
  )(UserSession.apply, unlift(UserSession.unapply))
}

case class UserSessionExternalIdKey(externalId: ExternalId[UserSession]) extends Key[UserSession] {
  override val version = 3
  val namespace = "user_session_by_external_id"
  def toKey(): String = externalId.id
}

class UserSessionExternalIdCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[UserSessionExternalIdKey, UserSession](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)

object UserSessionStates extends States[UserSession]

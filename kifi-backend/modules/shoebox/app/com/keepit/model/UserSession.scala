package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.id.Types.{UserSessionExternalId, UserSessionId}
import com.keepit.model.view.UserSessionView
import com.keepit.social.{SocialId, SocialNetworkType}
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

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
  def toUserSessionView: UserSessionView =
    UserSessionView(id.get, externalId, socialId, provider, expires, isValid, createdAt, updatedAt)
}

object UserSession {
  implicit def toUserSessionId(id: Id[UserSession]): UserSessionId = id.copy()
  implicit def toUserSessionExternalId(id: ExternalId[UserSession]): UserSessionExternalId = id.copy()
  implicit def fromUserSessionId(id: UserSessionId): Id[UserSession] = id.copy()
  implicit def fromUserSessionExternalId(id: UserSessionExternalId): ExternalId[UserSession] = id.copy()

  @deprecated(message = "remove when ShoeboxController#getSessionByExternalId is removed", since = "Sept 12, 2014")
  private implicit val idFormat = Id.format[UserSession]
  @deprecated(message = "remove when ShoeboxController#getSessionByExternalId is removed", since = "Sept 12, 2014")
  private implicit val userIdFormat = Id.format[User]
  @deprecated(message = "remove when ShoeboxController#getSessionByExternalId is removed", since = "Sept 12, 2014")
  private implicit val externalIdFormat = ExternalId.format[UserSession]
  @deprecated(message = "remove when ShoeboxController#getSessionByExternalId is removed", since = "Sept 12, 2014")
  private implicit val stateFormat = State.format[UserSession]

  @deprecated(message = "remove when ShoeboxController#getSessionByExternalId is removed", since = "Sept 12, 2014")
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

object UserSessionStates extends States[UserSession]

package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.id.Types.{UserSessionExternalId, UserSessionId}
import com.keepit.model.view.UserSessionView
import com.keepit.social.{SocialId, SocialNetworkType}
import org.joda.time.DateTime

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
}

object UserSessionStates extends States[UserSession]

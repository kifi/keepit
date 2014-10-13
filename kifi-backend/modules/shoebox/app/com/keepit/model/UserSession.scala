package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.shoebox.model.ids.UserSessionExternalId
import com.keepit.model.view.UserSessionView
import com.keepit.social.{ SocialId, SocialNetworkType }
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
    UserSessionView(socialId, provider, expires, isValid, createdAt, updatedAt)
}

object UserSession {
  implicit def toUserSessionExternalId(id: ExternalId[UserSession]): UserSessionExternalId = UserSessionExternalId(id.id)
  implicit def fromUserSessionExternalId(id: UserSessionExternalId): ExternalId[UserSession] = ExternalId[UserSession](id.id)
}

object UserSessionStates extends States[UserSession]

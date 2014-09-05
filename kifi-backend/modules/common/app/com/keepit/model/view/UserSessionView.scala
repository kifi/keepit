package com.keepit.model.view

import com.keepit.common.db.{ State, ExternalId, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.model.id.Types.{ UserSessionExternalId, UserSessionId }
import com.keepit.model.id.UserSessionRemoteModel
import com.keepit.social.{ SocialId, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class UserSessionView(
  id: UserSessionId,
  externalId: UserSessionExternalId,
  socialId: SocialId,
  provider: SocialNetworkType,
  expires: DateTime,
  valid: Boolean,
  createdAt: DateTime,
  updatedAt: DateTime)

object UserSessionView {
  private implicit val idFormat = Id.format[UserSessionRemoteModel]
  private implicit val userIdFormat = Id.format[User]
  private implicit val externalIdFormat = ExternalId.format[UserSessionRemoteModel]
  private implicit val stateFormat = State.format[UserSessionRemoteModel]

  implicit val userSessionFormat: Format[UserSessionView] = (
    (__ \ 'id).format[Id[UserSessionRemoteModel]] and
    (__ \ 'externalId).format[ExternalId[UserSessionRemoteModel]] and
    (__ \ 'socialId).format[String].inmap(SocialId.apply, unlift(SocialId.unapply)) and
    (__ \ 'provider).format[String].inmap(SocialNetworkType.apply, unlift(SocialNetworkType.unapply)) and
    (__ \ 'expires).format(DateTimeJsonFormat) and
    (__ \ 'valid).format[Boolean] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat)
  )(UserSessionView.apply, unlift(UserSessionView.unapply))
}


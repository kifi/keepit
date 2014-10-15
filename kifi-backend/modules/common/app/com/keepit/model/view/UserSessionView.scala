package com.keepit.model.view

import com.keepit.common.db.Id
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.social.{ SocialId, SocialNetworkType }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class UserSessionView(
  socialId: SocialId,
  provider: SocialNetworkType,
  expires: DateTime,
  valid: Boolean,
  createdAt: DateTime,
  updatedAt: DateTime)

object UserSessionView {
  private implicit val userIdFormat = Id.format[User]

  implicit val userSessionFormat: Format[UserSessionView] = (
    (__ \ 'socialId).format[String].inmap(SocialId.apply, unlift(SocialId.unapply)) and
    (__ \ 'provider).format[String].inmap(SocialNetworkType.apply, unlift(SocialNetworkType.unapply)) and
    (__ \ 'expires).format(DateTimeJsonFormat) and
    (__ \ 'valid).format[Boolean] and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat)
  )(UserSessionView.apply, unlift(UserSessionView.unapply))
}


package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.User
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial

class UserWithSocialSerializer extends Writes[UserWithSocial] {

  def writes(userWithSocial: UserWithSocial): JsValue =
    JsObject(List(
      "externalId"  -> JsString(userWithSocial.user.externalId.toString),
      "firstName" -> JsString(userWithSocial.user.firstName),
      "lastName"  -> JsString(userWithSocial.user.lastName),
      "facebookId"  -> JsString(userWithSocial.socialUserInfo.socialId.id)
      )
    )

  def writes (users: Seq[UserWithSocial]): JsValue =
    JsArray(users map { user =>
      UserWithSocialSerializer.userWithSocialSerializer.writes(user)
    })
}

object UserWithSocialSerializer {
  implicit val userWithSocialSerializer = new UserWithSocialSerializer
}

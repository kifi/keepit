package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.User
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial
import com.keepit.common.social.BasicUser

class BasicUserSerializer extends Writes[BasicUser] {
  def writes(basicUser: BasicUser): JsValue =
    JsObject(List(
      "externalId"  -> JsString(basicUser.externalId.toString),
      "firstName" -> JsString(basicUser.firstName),
      "lastName"  -> JsString(basicUser.lastName),
      "avatar" -> JsString(basicUser.avatar)
      )
    )

  def writes (users: Seq[BasicUser]): JsValue =
    JsArray(users map { user =>
      BasicUserSerializer.basicUserSerializer.writes(user)
    })
}

object BasicUserSerializer {
  implicit val basicUserSerializer = new BasicUserSerializer
}

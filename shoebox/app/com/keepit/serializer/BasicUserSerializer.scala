package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.social.BasicUser
import com.keepit.model.User

import play.api.libs.json._

class BasicUserSerializer extends Format[BasicUser] {

  def reads(json: JsValue): JsResult[BasicUser] = {
    JsSuccess(BasicUser(
      externalId = ExternalId[User]((json \ "externalId").as[String]),
      firstName = (json \ "firstName").as[String],
      lastName = (json \ "lastName").as[String],
      avatar = (json \ "avatar").as[String],
      facebookId = (json \ "facebookId").as[String]
    ))
  }

  def writes(basicUser: BasicUser): JsValue =
    JsObject(Seq(
      "externalId"  -> JsString(basicUser.externalId.toString),
      "firstName" -> JsString(basicUser.firstName),
      "lastName"  -> JsString(basicUser.lastName),
      "avatar" -> JsString(basicUser.avatar),
      "facebookId" -> JsString(basicUser.facebookId)
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

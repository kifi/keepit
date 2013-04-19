package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.social.BasicUser
import com.keepit.model.User

import play.api.libs.json._

class BasicUserSerializer extends Format[BasicUser] {
  def reads(json: JsValue): JsResult[BasicUser] =
    JsSuccess(BasicUser(
      externalId = ExternalId[User]((json \ "id").as[String]),
      firstName = (json \ "firstName").as[String],
      lastName = (json \ "lastName").as[String],
      avatar = (json \ "avatar").as[String],
      facebookId = (json \ "facebookId").as[String]))

  def writes(basicUser: BasicUser): JsValue =
    Json.obj(
      "id" -> basicUser.externalId.id,
      "firstName" -> basicUser.firstName,
      "lastName" -> basicUser.lastName,
      "avatar" -> basicUser.avatar,
      "facebookId" -> basicUser.facebookId)

  def writes(basicUsers: Seq[BasicUser]): JsValue =
    JsArray(basicUsers map writes)
}

object BasicUserSerializer {
  implicit val basicUserSerializer = new BasicUserSerializer
}

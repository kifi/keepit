package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.{User, UserCxRepo}
import play.api.libs.json._
import com.keepit.common.social.UserWithSocial
import com.keepit.common.db._

class UserSerializer extends Format[User] {

  def writes(user: User): JsValue =
    JsObject(List(
      "id"  -> user.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> JsString(user.createdAt.toStandardTimeString),
      "updatedAt" -> JsString(user.updatedAt.toStandardTimeString),
      "externalId" -> JsString(user.externalId.id),
      "firstName"  -> JsString(user.firstName),
      "lastName"  -> JsString(user.lastName)
    )
    )

  def reads(json: JsValue): User =
    User(
      id = (json \ "id").asOpt[Long].map(Id[User](_)),
      createdAt = parseStandardTime((json \ "createdAt").as[String]),
      updatedAt = parseStandardTime((json \ "updatedAt").as[String]),
      externalId = ExternalId[User]((json \ "externalId").as[String]),
      firstName = (json \ "firstName").as[String],
      lastName = (json \ "lastName").as[String]
    )
}

object UserSerializer {
  implicit val userSerializer = new UserSerializer
}

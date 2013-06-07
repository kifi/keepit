package com.keepit.serializer

import org.joda.time.DateTime

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.User

import play.api.libs.json._

class UserSerializer extends Format[User] {

  def writes(user: User): JsValue =
    JsObject(List(
      "id"  -> user.id.map(u => JsNumber(u.id)).getOrElse(JsNull),
      "createdAt" -> Json.toJson(user.createdAt),
      "updatedAt" -> Json.toJson(user.updatedAt),
      "externalId" -> JsString(user.externalId.id),
      "firstName"  -> JsString(user.firstName),
      "lastName"  -> JsString(user.lastName),
      "state" -> JsString(user.state.value)
    ))

  def reads(json: JsValue): JsResult[User] =
    JsSuccess(User(
      id = (json \ "id").asOpt[Long].map(Id[User](_)),
      createdAt = (json \ "createdAt").as[DateTime],
      updatedAt = (json \ "updatedAt").as[DateTime],
      externalId = ExternalId[User]((json \ "externalId").as[String]),
      firstName = (json \ "firstName").as[String],
      lastName = (json \ "lastName").as[String],
      state = State[User]((json \ "state").as[String])
    ))
}

object UserSerializer {
  implicit val userSerializer = new UserSerializer
}

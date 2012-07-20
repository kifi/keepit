package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.User

import play.api.libs.json._

class UserSerializer extends Serializer[User] {
  
  def writes(user: User): JsValue =
    JsObject(List(
      "exuuid"  -> JsString(user.externalId.toString),
      "qfields" -> JsObject(List(
        "firstName" -> asValue(user.firstName),
        "lastName"  -> asValue(user.lastName))
      ))
    )

  def writes (users: Seq[User]): JsValue = 
    JsArray(users map { user => 
      JsObject(List(
        "userId" -> JsNumber(user.id.get.id),
        "userObject" -> UserSerializer.userSerializer.writes(user)
      ))
    })

  def reads(json: JsValue) = User(
    externalId = (json \ "exuuid").asOpt[String].map(ExternalId[User](_)).getOrElse(ExternalId()),
    firstName = fieldsValue(json, "firstName").as[String],
    lastName = fieldsValue(json, "lastName").as[String]
  )
    
  private def fieldsValue(json: JsValue, name: String): JsValue = (json \ "qfields" \ name \ "value") 
}

object UserSerializer {
  implicit val userSerializer = new UserSerializer
}

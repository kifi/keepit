package com.keepit.serializer

import com.keepit.common.db.ExternalId
import com.keepit.common.time._
import com.keepit.model.User

import play.api.libs.json._

class UserSerializer extends Writes[User] {
  
  def writes(user: User): JsValue =
    JsObject(List(
      "exuuid"  -> JsString(user.externalId.toString),
      "firstName" -> JsString(user.firstName),
      "lastName"  -> JsString(user.lastName),
      "facebookId"  -> { user.facebookId map {id => JsString(id.value)} getOrElse (JsNull) }
      )
    )

  def writes (users: Seq[User]): JsValue = 
    JsArray(users map { user => 
      UserSerializer.userSerializer.writes(user)
    })
}

object UserSerializer {
  implicit val userSerializer = new UserSerializer
}

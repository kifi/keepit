package com.keepit.social

import play.api.libs.json._
import play.api.libs.functional.syntax._


case class NonUserKind(name: String)
object NonUserKinds {
  val email = NonUserKind("email")
}

case class BasicNonUser(kind: NonUserKind, id: String, firstName: Option[String], lastName: Option[String]) extends BasicUserLikeEntity
object BasicNonUser {
  implicit val nonUserTypeFormat = Json.format[NonUserKind]

  // The following formatter can be replaced with the functional Play formatter once we can break backwards compatibility.
//  (
//    (__ \ 'kind).format[NonUserKind] and
//      (__ \ 'id).format[String] and
//      (__ \ 'firstName).formatNullable[String] and
//      (__ \ 'lastName).formatNullable[String]
//    )(BasicNonUser.apply, unlift(BasicNonUser.unapply))

  // Be aware that BasicUserLikeEntity uses the `kind` field to detect if its a BasicUser or BasicNonUser
  implicit val basicNonUserFormat = new Format[BasicNonUser] {
    def reads(json: JsValue): JsResult[BasicNonUser] = {
      JsSuccess(BasicNonUser(
        kind = (json \ "kind").as[NonUserKind],
        id = (json \ "id").as[String],
        firstName = (json \ "firstName").asOpt[String],
        lastName = (json \ "lastName").asOpt[String]
      ))
    }
    def writes(entity: BasicNonUser): JsValue = {
      Json.obj(
        "kind" -> entity.kind,
        "id" -> entity.id,
        "firstName" -> (entity.firstName.getOrElse(entity.id): String),
        "lastName" -> entity.lastName
      )
    }
  }
}

package com.keepit.social

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class NonUserKind(name: String)
object NonUserKind {
  implicit val nonUserKindFormat = new Format[NonUserKind] {
    def reads(json: JsValue): JsResult[NonUserKind] = {
      json.asOpt[String] match {
        case Some(str) => JsSuccess(NonUserKind(str))
        case None => JsError()
      }
    }

    def writes(kind: NonUserKind): JsValue = {
      JsString(kind.name)
    }
  }
}
object NonUserKinds {
  val email = NonUserKind("email")
}

case class BasicNonUser(kind: NonUserKind, id: String, firstName: Option[String], lastName: Option[String]) extends BasicUserLikeEntity {
  override def asBasicNonUser: Option[BasicNonUser] = Some(this)
}

object BasicNonUser {
  // The following formatter can be replaced with the functional Play formatter once we can break backwards compatibility.
  //  (
  //    (__ \ 'kind).format[NonUserKind] and
  //      (__ \ 'id).format[String] and
  //      (__ \ 'firstName).formatNullable[String] and
  //      (__ \ 'lastName).formatNullable[String]
  //    )(BasicNonUser.apply, unlift(BasicNonUser.unapply))

  // Be aware that BasicUserLikeEntity uses the `kind` field to detect if its a BasicUser or BasicNonUser
  private implicit val nonUserKindFormat = NonUserKind.nonUserKindFormat
  implicit val format = new Format[BasicNonUser] {
    def reads(json: JsValue): JsResult[BasicNonUser] = {
      JsSuccess(BasicNonUser(
        kind = NonUserKind((json \ "kind").as[String]),
        id = (json \ "id").as[String],
        firstName = (json \ "firstName").asOpt[String],
        lastName = (json \ "lastName").asOpt[String]
      ))
    }
    def writes(entity: BasicNonUser): JsValue = {
      Json.obj(
        "kind" -> entity.kind.name,
        "id" -> entity.id,
        "firstName" -> (entity.firstName.getOrElse(entity.id): String),
        "lastName" -> (entity.lastName.getOrElse(""): String),
        "pictureName" -> "0.jpg" // todo: remove! So it's not undefined for old extensions. The icon will be broken though.
      )
    }
  }
}

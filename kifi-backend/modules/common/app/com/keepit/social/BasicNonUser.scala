package com.keepit.social

import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.S3UserPictureConfig
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
  val slack = NonUserKind("slack")
  val twitter = NonUserKind("twitter")
  def fromAuthorKind(kind: AuthorKind): Option[NonUserKind] = kind match {
    case AuthorKind.Kifi => None
    case AuthorKind.Slack => Some(slack)
    case AuthorKind.Twitter => Some(twitter)
    case AuthorKind.Email => Some(email)
  }
}

case class BasicNonUser(kind: NonUserKind, id: String, firstName: Option[String], lastName: Option[String], pictureName: String) {
  def asEmailAddress: Option[EmailAddress] = kind match {
    case NonUserKinds.email => Some(EmailAddress(id))
    case _ => None
  }
}

object BasicNonUser {
  val defaultEmailPictureName = S3UserPictureConfig.defaultName + ".jpg"
  def fromEmail(email: EmailAddress): BasicNonUser = BasicNonUser(NonUserKinds.email, id = email.address, firstName = None, lastName = None, pictureName = defaultEmailPictureName)
  def fromBasicAuthor(author: BasicAuthor): Option[BasicNonUser] = NonUserKinds.fromAuthorKind(author.kind).map { kind =>
    BasicNonUser(kind, author.id, Some(author.name), None, pictureName = author.picture)
  }

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
        lastName = (json \ "lastName").asOpt[String],
        pictureName = (json \ "pictureName").as[String]
      ))
    }
    def writes(entity: BasicNonUser): JsValue = {
      Json.obj(
        "kind" -> entity.kind.name,
        "id" -> entity.id,
        "firstName" -> (entity.firstName.getOrElse(entity.id): String),
        "lastName" -> (entity.lastName.getOrElse(""): String),
        "pictureName" -> entity.pictureName
      )
    }
  }
}

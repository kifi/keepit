package com.keepit.common.mail.template

import com.keepit.common.db.Id
import com.keepit.common.mail.{ ElectronicMailCategory, EmailAddress }
import com.keepit.model.User
import com.keepit.serializer.EitherFormat
import play.twirl.api.Html

case class EmailToSend(
  title: String = "Kifi",
  from: EmailAddress,
  fromName: Option[String] = Some("Kifi"),
  to: Either[Id[User], EmailAddress],
  cc: Seq[EmailAddress] = Seq[EmailAddress](),
  subject: String,
  htmlTemplate: Html,
  category: ElectronicMailCategory,
  campaign: Option[String] = None,
  senderUserId: Option[Id[User]] = None,
  tips: Seq[EmailTips] = Seq.empty)

object EmailToSend {
  import com.keepit.common.mail.ElectronicMail.emailCategoryFormat
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val htmlFormat = new Format[Html] {
    def reads(js: JsValue) = JsSuccess(Html(js.as[JsString].value))

    def writes(o: Html) = Json.toJson(o.body)
  }

  val toFormat = EitherFormat[Id[User], EmailAddress]

  implicit val emailToSendFormat: Format[EmailToSend] = (
    (__ \ 'title).format[String] and
    (__ \ 'from).format[EmailAddress] and
    (__ \ 'fromName).formatNullable[String] and
    (__ \ 'to).format(toFormat) and
    (__ \ 'cc).format[Seq[EmailAddress]] and
    (__ \ 'subject).format[String] and
    (__ \ 'htmlTemplate).format[Html] and
    (__ \ 'category).format[ElectronicMailCategory] and
    (__ \ 'campaign).format[Option[String]] and
    (__ \ 'senderUserId).formatNullable[Id[User]] and
    (__ \ 'tips).format[Seq[EmailTips]]
  )(EmailToSend.apply, unlift(EmailToSend.unapply))
}


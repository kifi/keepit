package com.keepit.common.mail.template

import com.keepit.common.db.Id
import com.keepit.common.mail.{ ElectronicMailCategory, EmailAddress }
import com.keepit.heimdal.{ HeimdalContext, ContextStringData, ContextData }
import com.keepit.model.User
import play.twirl.api.Html
import com.keepit.common.json.EitherFormat

sealed trait EmailLayout { val name: String }

object EmailLayout {
  private[EmailLayout] sealed case class EmailLayoutImpl(name: String) extends EmailLayout

  object DefaultLayout extends EmailLayoutImpl("default")
  object CustomLayout extends EmailLayoutImpl("custom")

  val ALL = CustomLayout :: DefaultLayout :: Nil

  def apply(name: String) =
    ALL.find(_.name == name).getOrElse(throw new IllegalArgumentException(s"$name is not a recognized EmailLayout"))

  implicit def toContextData(v: EmailLayout): ContextData = ContextStringData(v)

  private val fromContextDataPf: PartialFunction[ContextData, EmailLayout] = {
    case ContextStringData(value) => EmailLayoutImpl(value)
  }

  implicit def fromContextData(v: ContextData): Option[EmailLayout] = Some(v) collect fromContextDataPf
  implicit def fromContextData(v: Option[ContextData]): Option[EmailLayout] = v collect fromContextDataPf

  implicit def toString(v: EmailLayout): String = v.name
  implicit def fromString(v: String): EmailLayout = EmailLayoutImpl(v)
}

object TemplateOptions {
  val CustomLayout: (String, ContextData) = ("layout", EmailLayout.CustomLayout)
  val DefaultLayout: (String, ContextData) = ("layout", EmailLayout.DefaultLayout)
}

case class EmailToSend(
  title: String = "Kifi",
  from: EmailAddress,
  fromName: Option[Either[Id[User], String]] = Some(Right("Kifi")),
  to: Either[Id[User], EmailAddress],
  cc: Seq[EmailAddress] = Seq[EmailAddress](),
  subject: String,
  htmlTemplate: Html,
  textTemplate: Option[Html] = None,
  category: ElectronicMailCategory,
  campaign: Option[String] = None,
  senderUserId: Option[Id[User]] = None,
  tips: Seq[EmailTip] = Seq.empty,
  templateOptions: Map[String, ContextData] = Map.empty,
  extraHeaders: Option[Map[String, String]] = None,
  auxiliaryData: Option[HeimdalContext] = None)

object EmailToSend {
  import com.keepit.common.mail.ElectronicMail.emailCategoryFormat
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val htmlFormat = new Format[Html] {
    def reads(js: JsValue) = JsSuccess(Html(js.as[JsString].value))

    def writes(o: Html) = Json.toJson(o.body)
  }

  val toFormat = EitherFormat[Id[User], EmailAddress]
  val fromNameFormat = EitherFormat[Id[User], String]

  implicit val emailToSendFormat: Format[EmailToSend] = (
    (__ \ 'title).format[String] and
    (__ \ 'from).format[EmailAddress] and
    (__ \ 'fromName).formatNullable(fromNameFormat) and
    (__ \ 'to).format(toFormat) and
    (__ \ 'cc).format[Seq[EmailAddress]] and
    (__ \ 'subject).format[String] and
    (__ \ 'htmlTemplate).format[Html] and
    (__ \ 'textTemplate).formatNullable[Html] and
    (__ \ 'category).format[ElectronicMailCategory] and
    (__ \ 'campaign).format[Option[String]] and
    (__ \ 'senderUserId).formatNullable[Id[User]] and
    (__ \ 'tips).format[Seq[EmailTip]] and
    (__ \ 'templateOptions).format[Map[String, ContextData]] and
    (__ \ 'extraHeaders).formatNullable[Map[String, String]] and
    (__ \ 'auxiliaryData).formatNullable[HeimdalContext]
  )(EmailToSend.apply, unlift(EmailToSend.unapply))
}


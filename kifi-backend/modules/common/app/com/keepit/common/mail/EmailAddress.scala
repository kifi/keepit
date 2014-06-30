package com.keepit.common.mail

import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import play.api.data.validation.ValidationError
import scala.util.{Success, Failure, Try}

case class EmailAddress(address: String) extends AnyVal {
  override def toString = address
}

object EmailAddress {
  implicit val format: Format[EmailAddress] =
    Format(__.read[String].filter(ValidationError("Invalid email address"))(EmailAddress.isValid).map(EmailAddress.validated(_)), new Writes[EmailAddress]{ def writes(o: EmailAddress) = JsString(o.address) })

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[EmailAddress] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, EmailAddress]] = {
      stringBinder.bind(key, params) map {
        case Right(address) if isValid(address)=> Right(EmailAddress.validated(address))
        case _ => Left("Unable to bind a valid EmailAddress")
      }
    }
    override def unbind(key: String, emailAddress: EmailAddress): String = {
      stringBinder.unbind(key, emailAddress.address)
    }
  }

  // Regex from http://www.whatwg.org/specs/web-apps/current-work/multipage/states-of-the-type-attribute.html
  private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private def isValid(address: String): Boolean = emailRegex.findFirstIn(address).isDefined
  private def validated(address: String): EmailAddress = EmailAddress(address.toLowerCase)
  def validate(address: String): Try[EmailAddress] = {
    if (isValid(address)) Success(validated(address))
    else Failure(new IllegalArgumentException(s"Invalid email address: $address"))
  }
}

object SystemEmailAddress {
  val TEAM = EmailAddress("team@42go.com")
  val NOTIFICATIONS = EmailAddress("notifications@kifi.com")
  val ENG = EmailAddress("eng@42go.com")
  val EISHAY = EmailAddress("eishay@42go.com")
  val INVITATION = EmailAddress("invitation@kifi.com")
  val YASUHIRO = EmailAddress("yasuhiro@42go.com")
  val ANDREW = EmailAddress("andrew@42go.com")
  val JARED = EmailAddress("jared@42go.com")
  val YINGJIE = EmailAddress("yingjie@42go.com")
  val LÉO = EmailAddress("leo@42go.com")
  val STEPHEN = EmailAddress("stephen@42go.com")
  val EFFI = EmailAddress("effi@42go.com")
  val EDUARDO = EmailAddress("eduardo@42go.com")
  val RAY = EmailAddress("ray@42go.com")
  val MARTIN = EmailAddress("martin@42go.com")
  val CONGRATS = EmailAddress("congrats@kifi.com")
  val NOTIFY = EmailAddress("42.notify@gmail.com")
  val SENDGRID = EmailAddress("sendgrid@42go.com")
  val SUPPORT = EmailAddress("support@kifi.com")
  val OLD_SUPPORT = EmailAddress("support@42go.com")//keep for serialization of mail

  val ENG_EMAILS = Seq(EISHAY, YASUHIRO, JARED, ANDREW, YINGJIE, LÉO, STEPHEN, RAY, MARTIN)
  val NON_ENG_EMAILS = Seq(TEAM, INVITATION, SUPPORT, OLD_SUPPORT, NOTIFICATIONS, ENG, CONGRATS, EDUARDO, EFFI, NOTIFY, SENDGRID)

  val ALL_EMAILS = ENG_EMAILS ++ NON_ENG_EMAILS

  def discussion(id: String): EmailAddress = EmailAddress("discuss+" + id + "@kifi.com")

  def isValid(email: EmailAddress): Boolean = {
    ALL_EMAILS.contains(email) || (email.address.startsWith("discuss+") && email.address.endsWith("@kifi.com"))
  }
}

case class BasicContact(email: EmailAddress, name: Option[String] = None, firstName:Option[String] = None, lastName:Option[String] = None)

object BasicContact {

  // Parsing "email-like" expressions containing a name, such as "Douglas Adams <doug@kifi.com>"
  private val contactRegex = """\s*([^\s<][^<]*[^\s<])\s+<(.*)>""".r
  def fromString(contact: String): Try[BasicContact] = contact match {
    case contactRegex(name, address) => EmailAddress.validate(address).map { validEmail => BasicContact(validEmail, name = Some(name)) }
    case _ => EmailAddress.validate(contact).map(BasicContact(_))
  }

  private val readsFromString = Reads[BasicContact](_.validate[String].flatMap { contact =>
    fromString(contact).map(JsSuccess(_)).recover { case ex: Throwable => JsError(ex.getMessage()) }.get
  })
  implicit val format: Format[BasicContact] = Format(Json.reads[BasicContact] orElse readsFromString, Json.writes[BasicContact])
}

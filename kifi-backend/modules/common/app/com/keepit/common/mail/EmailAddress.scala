package com.keepit.common.mail

import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import scala.util.{ Failure, Success, Try }
import play.api.data.{ Forms, Mapping }
import com.keepit.abook.model.RichContact

case class EmailAddress(address: String) {
  override def toString = address
  def equalsIgnoreCase(other: EmailAddress): Boolean = compareToIgnoreCase(other) == 0
  def compareToIgnoreCase(other: EmailAddress): Int = address.compareToIgnoreCase(other.address)
}

object EmailAddress {
  implicit val format = new Format[EmailAddress] {
    def reads(json: JsValue) = for {
      address <- json.validate[String]
      validAddress <- validate(address).map(JsSuccess(_)).recover { case ex: Throwable => JsError(ex.getMessage) }.get
    } yield validAddress

    def writes(email: EmailAddress) = JsString(email.address)
  }

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[EmailAddress] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, EmailAddress]] = {
      stringBinder.bind(key, params) map {
        case Right(address) => validate(address).map(Right(_)).recover { case ex: Throwable => Left(ex.getMessage) }.get
        case _ => Left("Unable to bind a valid email address String")
      }
    }
    override def unbind(key: String, emailAddress: EmailAddress): String = {
      stringBinder.unbind(key, emailAddress.address)
    }
  }

  implicit val formMapping: Mapping[EmailAddress] = {
    import play.api.data.format.Formats._
    Forms.of[String].verifying("error.email", validate(_).isSuccess).transform(validate(_).get, _.address)
  }

  // Regex from http://www.whatwg.org/specs/web-apps/current-work/multipage/states-of-the-type-attribute.html
  private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  private def isValid(address: String): Boolean = emailRegex.findFirstIn(address.trim).isDefined

  private def canonicalize(address: String): String = {
    val (localAt, host) = address.trim.splitAt(address.lastIndexOf('@') + 1)
    localAt + host.toLowerCase
  }

  def validate(address: String): Try[EmailAddress] = {
    val trimmed = address.trim
    if (isValid(trimmed)) {
      Success(EmailAddress(canonicalize(trimmed)))
    } else {
      Failure(new IllegalArgumentException(s"Invalid email address: $address"))
    }
  }

  // might also consider these indicators in the future:
  // support feedback comment notification tickets? bugs? buganizer system nobody lists? announce(ments?)?
  // discuss help careers jobs reports? bounces? updates?
  private val botEmailAddressRe = """(?:\+[^@]|\d{10}|\b(?i)(?:(?:no)?reply|(?:un)?subscribe)\b)""".r
  def isLikelyHuman(email: EmailAddress): Boolean = {
    botEmailAddressRe.findFirstIn(email.address.trim).isEmpty
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
  val JOSH = EmailAddress("josh@kifi.com")
  val AARON = EmailAddress("aaron@kifi.com")
  val MARK = EmailAddress("mark@kifi.com")
  val CONGRATS = EmailAddress("congrats@kifi.com")
  val NOTIFY = EmailAddress("42.notify@gmail.com")
  val SENDGRID = EmailAddress("sendgrid@42go.com")
  val SUPPORT = EmailAddress("support@kifi.com")
  val OLD_SUPPORT = EmailAddress("support@42go.com") //keep for serialization of mail
  val FEED_QA = EmailAddress("feed-qa@kifi.com")

  val ENG_EMAILS = Seq(EISHAY, YASUHIRO, JARED, ANDREW, YINGJIE, LÉO, STEPHEN, JOSH)
  val NON_ENG_EMAILS = Seq(TEAM, INVITATION, SUPPORT, OLD_SUPPORT, NOTIFICATIONS, ENG, CONGRATS, NOTIFY, SENDGRID)

  val ALL_EMAILS = ENG_EMAILS ++ NON_ENG_EMAILS

  def discussion(id: String): EmailAddress = EmailAddress("discuss+" + id + "@kifi.com")

  def isValid(email: EmailAddress): Boolean = {
    ALL_EMAILS.contains(email) || (email.address.startsWith("discuss+") && email.address.endsWith("@kifi.com"))
  }
}

case class BasicContact(email: EmailAddress, name: Option[String] = None, firstName: Option[String] = None, lastName: Option[String] = None)

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

  def fromRichContact(richContact: RichContact): BasicContact = BasicContact(richContact.email, richContact.name, richContact.firstName, richContact.lastName)
}

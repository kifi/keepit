package com.keepit.common.mail

import play.api.libs.json._
import play.api.mvc.QueryStringBindable
import com.keepit.model.Name
import scala.util.{Failure, Success, Try}

case class EmailAddress(address: String) extends AnyVal {
  override def toString = address
}

object EmailAddress {
  implicit val format: Format[EmailAddress] =
    Format(__.read[String].map(s => EmailAddress.validate(s)), new Writes[EmailAddress]{ def writes(o: EmailAddress) = JsString(o.address) })

  implicit def queryStringBinder[T](implicit stringBinder: QueryStringBindable[String]) = new QueryStringBindable[EmailAddress] {
    override def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, EmailAddress]] = {
      stringBinder.bind(key, params) map {
        case Right(address) => Try(EmailAddress.validate(address)) match {
          case Success(validEmail) => Right(validEmail)
          case Failure(ex) => Left(ex.getMessage)
        }
        case _ => Left("Unable to bind an EmailAddress")
      }
    }
    override def unbind(key: String, emailAddress: EmailAddress): String = {
      stringBinder.unbind(key, emailAddress.address)
    }
  }

  private val emailRegex = """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$""".r

  def validate(address: String): EmailAddress = {
    if (emailRegex.findFirstIn(address).isEmpty) { throw new IllegalArgumentException(s"Invalid email address: $address") }
    EmailAddress(address.toLowerCase)
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

  def validate(email: EmailAddress): Boolean = {
    ALL_EMAILS.contains(email) || (email.address.startsWith("discuss+") && email.address.endsWith("@kifi.com"))
  }
}

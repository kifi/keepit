package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.User
import play.api.libs.json.{ JsString, Format }
import play.api.libs.json._

import scala.util.Try

sealed trait Recipient {
  val kind: String
}

case class UserRecipient(id: Id[User]) extends Recipient {
  override val kind = "user"
  override def toString = id.toString
}

case class EmailRecipient(address: EmailAddress) extends Recipient {
  override val kind = "email"
  override def toString = address.toString
}

object Recipient {
  def serialize(recip: Recipient): String = s"${recip.kind}|${recip.toString}"
  def deserialize(str: String): Option[Recipient] = {
    val pipeIndex = str.indexOf('|')
    val (kind, rest) = (str.substring(0, pipeIndex), str.substring(pipeIndex + 1))

    kind match {
      case "user" => Try(UserRecipient(Id(rest.toLong))).toOption
      case "email" => EmailAddress.validate(rest).map(EmailRecipient.apply).toOption
      case _ => None
    }
  }

  def apply(str: String): Recipient = deserialize(str).get
  def unapply(recip: Recipient): Option[String] = Some(serialize(recip))

  def fromUser(userId: Id[User]) = UserRecipient(userId)
  def fromEmail(email: EmailAddress) = EmailRecipient(email)

  implicit val format = Format[Recipient](
    __.read[String].map(str => Recipient(str)),
    Writes { obj => JsString(serialize(obj)) }
  )
}

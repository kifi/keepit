package com.keepit.notify.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model.User
import play.api.libs.json.{ JsString, Format }
import play.api.libs.json._

sealed trait Recipient {
  val kind: String
}

case class UserRecipient(id: Id[User], experimentEnabled: Option[Boolean] = None) extends Recipient {
  override val kind = "user"

  override def toString = id.toString

  override def equals(that: Any): Boolean = that match {
    case UserRecipient(id, _) if id == this.id => true
    case _ => false
  }
}

case class EmailRecipient(address: EmailAddress) extends Recipient {
  override val kind = "email"

  override def toString = address.toString
}

object Recipient {

  def apply(str: String): Recipient = {
    val pipeIndex = str.indexOf('|')
    val (kind, rest) = (str.substring(0, pipeIndex), str.substring(pipeIndex + 1))

    kind match {
      case "user" => UserRecipient(Id(rest.toLong))
      case "email" => EmailAddress.validate(rest).map(EmailRecipient.apply).get
      case _ => throw new IllegalArgumentException(s"${str} does not have a valid kind")
    }
  }

  def apply[T](obj: T)(implicit evidence: T => AsRecipient[T]) = evidence(obj).recipient

  def unapply(recip: Recipient): Option[String] = Some(s"${recip.kind}|${recip.toString}")

  implicit val format = Format[Recipient](
    __.read[String].map(Recipient.apply),
    Writes[Recipient](obj => JsString(Recipient.unapply(obj).get))
  )

  trait AsRecipient[T] {
    def recipient: Recipient
  }

  implicit class UserIdAsRecipient(user: Id[User]) extends AsRecipient[Id[User]] {
    def recipient = UserRecipient(user)
  }

  implicit class UserAsRecipient(user: User) extends AsRecipient[User] {
    def recipient = user.id.get.recipient
  }

  implicit class EmailAddressAsRecipient(email: EmailAddress) extends AsRecipient[EmailAddress] {
    def recipient = EmailRecipient(email)
  }

}

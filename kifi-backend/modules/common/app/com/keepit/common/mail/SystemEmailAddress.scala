package com.keepit.common.mail

case class EmailAddress(address: String) extends AnyVal

trait EmailAddressHolder {
  val address: String
  override def equals(obj: Any) = obj.isInstanceOf[EmailAddressHolder] && obj.asInstanceOf[EmailAddressHolder].address == address
  override def hashCode = address.hashCode
  override def toString() = address
}

object EmailAddressHolder {
  implicit def toEmailAddress(holder: EmailAddressHolder): EmailAddress = EmailAddress(holder.address)
}

sealed abstract class SystemEmailAddress(val address: String) extends EmailAddressHolder

object SystemEmailAddress {
  case object TEAM extends SystemEmailAddress("team@42go.com")
  case object NOTIFICATIONS extends SystemEmailAddress("notifications@kifi.com")
  case object ENG extends SystemEmailAddress("eng@42go.com")
  case object EISHAY extends SystemEmailAddress("eishay@42go.com")
  case object INVITATION extends SystemEmailAddress("invitation@kifi.com")
  case object YASUHIRO extends SystemEmailAddress("yasuhiro@42go.com")
  case object ANDREW extends SystemEmailAddress("andrew@42go.com")
  case object JARED extends SystemEmailAddress("jared@42go.com")
  case object YINGJIE extends SystemEmailAddress("yingjie@42go.com")
  case object LÉO extends SystemEmailAddress("leo@42go.com")
  case object STEPHEN extends SystemEmailAddress("stephen@42go.com")
  case object EFFI extends SystemEmailAddress("effi@42go.com")
  case object EDUARDO extends SystemEmailAddress("eduardo@42go.com")
  case object RAY extends SystemEmailAddress("ray@42go.com")
  case object MARTIN extends SystemEmailAddress("martin@42go.com")
  case object CONGRATS extends SystemEmailAddress("congrats@kifi.com")
  case object NOTIFY extends SystemEmailAddress("42.notify@gmail.com")
  case object SENDGRID extends SystemEmailAddress("sendgrid@42go.com")
  case object SUPPORT extends SystemEmailAddress("support@kifi.com")
  case object OLD_SUPPORT extends SystemEmailAddress("support@42go.com")//keep for serialization of mail
  case class  DISCUSSION(override val address: String) extends SystemEmailAddress(address)

  val ENG_EMAILS = Seq(EISHAY, YASUHIRO, JARED, ANDREW, YINGJIE, LÉO, STEPHEN, RAY, MARTIN)
  val NON_ENG_EMAILS = Seq(TEAM, INVITATION, SUPPORT, OLD_SUPPORT, NOTIFICATIONS, ENG, CONGRATS, EDUARDO, EFFI, NOTIFY, SENDGRID)

  val ALL_EMAILS = ENG_EMAILS ++ NON_ENG_EMAILS

  def apply(email: String): SystemEmailAddress =
    ALL_EMAILS.find(_.address == email).getOrElse{
      if (email.startsWith("discuss+")) {
        DISCUSSION(email)
      } else {
        throw new IllegalArgumentException(s"No system email for $email")
      }
    }

  def discussion(id: String): SystemEmailAddress = DISCUSSION("discuss+" + id + "@kifi.com")
}

package com.keepit.common.mail

trait EmailAddressHolder {
  val address: String
  override def equals(obj: Any) = obj.isInstanceOf[EmailAddressHolder] && obj.asInstanceOf[EmailAddressHolder].address == address
  override def hashCode = address.hashCode
}

case class GenericEmailAddress(address: String) extends EmailAddressHolder

sealed abstract class SystemEmailAddress(val address: String) extends EmailAddressHolder

object EmailAddresses {
  case object TEAM extends SystemEmailAddress("team@42go.com")
  case object SUPPORT extends SystemEmailAddress("support@42go.com")
  case object NOTIFICATIONS extends SystemEmailAddress("notifications@kifi.com")
  case object ENG extends SystemEmailAddress("eng@42go.com")
  case object EISHAY extends SystemEmailAddress("eishay@42go.com")
  case object INVITATION extends SystemEmailAddress("invitation@42go.com")
  case object YASUHIRO extends SystemEmailAddress("yasuhiro@42go.com")
  case object ANDREW extends SystemEmailAddress("andrew@42go.com")
  case object JARED extends SystemEmailAddress("jared@42go.com")
  case object GREG extends SystemEmailAddress("greg@42go.com")
  case object YINGJIE extends SystemEmailAddress("yingjie@42go.com")
  case object LÉO extends SystemEmailAddress("leo@42go.com")
  case object STEPHEN extends SystemEmailAddress("stephen@42go.com")
  case object EFFI extends SystemEmailAddress("effi@42go.com")
  case object EDUARDO extends SystemEmailAddress("eduardo@42go.com")
  case object RAY extends SystemEmailAddress("ray@42go.com")
  case object CONGRATS extends SystemEmailAddress("congrats@kifi.com")
  case object ASANA_PROD_HEALTH extends SystemEmailAddress("x+7368498674275@mail.asana.com")

  val ENG_EMAILS = Seq(EISHAY, YASUHIRO, JARED, GREG, ANDREW, YINGJIE, LÉO, STEPHEN, RAY)
  val NON_ENG_EMAILS = Seq(TEAM, INVITATION, SUPPORT, NOTIFICATIONS, ENG, CONGRATS, ASANA_PROD_HEALTH, EDUARDO, EFFI)

  val ALL_EMAILS = ENG_EMAILS ++ NON_ENG_EMAILS

  def apply(email: String): SystemEmailAddress =
    ALL_EMAILS.find(_.address == email).getOrElse(throw new IllegalArgumentException(s"No system email for $email"))

}

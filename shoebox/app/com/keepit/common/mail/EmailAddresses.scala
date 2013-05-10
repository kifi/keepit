package com.keepit.common.mail

trait EmailAddressHolder {
  val address: String
  override def equals(obj: Any) = obj.isInstanceOf[EmailAddressHolder] && obj.asInstanceOf[EmailAddressHolder].address == address
  override def hashCode = address.hashCode
}

case class GenericEmailAddress(val address: String) extends EmailAddressHolder

sealed abstract class SystemEmailAddress(val address: String) extends EmailAddressHolder

object EmailAddresses {
  case object TEAM extends SystemEmailAddress("team@42go.com")
  case object SUPPORT extends SystemEmailAddress("support@42go.com")
  case object NOTIFICATIONS extends SystemEmailAddress("notifications@kifi.com")
  case object ENG extends SystemEmailAddress("eng@42go.com")
  case object EISHAY extends SystemEmailAddress("eishay@42go.com")
  case object DANNY extends SystemEmailAddress("danny@42go.com")
  case object EFFI extends SystemEmailAddress("effi@42go.com")
  case object INVITATION extends SystemEmailAddress("invitation@42go.com")
  case object YASUHIRO extends SystemEmailAddress("yasuhiro@42go.com")
  case object ANDREW extends SystemEmailAddress("andrew@42go.com")
  case object JARED extends SystemEmailAddress("jared@42go.com")
  case object GREG extends SystemEmailAddress("greg@42go.com")
  case object CONGRATS extends SystemEmailAddress("congrats@kifi.com")
  case object ASANA_PROD_HEALTH extends SystemEmailAddress("x+5363166029963@mail.asana.com")

  def apply(email: String): SystemEmailAddress = email match {
    case TEAM.address => TEAM
    case SUPPORT.address => SUPPORT
    case NOTIFICATIONS.address => NOTIFICATIONS
    case ENG.address => ENG
    case EISHAY.address => EISHAY
    case YASUHIRO.address => YASUHIRO
    case ANDREW.address => ANDREW
    case JARED.address => JARED
    case GREG.address => GREG
    case CONGRATS.address => CONGRATS
    case ASANA_PROD_HEALTH.address => ASANA_PROD_HEALTH
  }
}

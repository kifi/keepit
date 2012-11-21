package com.keepit.common.mail
import com.keepit.model.EmailAddress

trait EmailAddressHolder {
  val address: String
}

sealed abstract class SystemEmailAddress(val address: String) extends EmailAddressHolder

object EmailAddresses {
  case object TEAM extends SystemEmailAddress("team@42go.com")
  case object SUPPORT extends SystemEmailAddress("support@42go.com")
  case object ENG extends SystemEmailAddress("eng@42go.com")
  case object EISHAY extends SystemEmailAddress("eishay@42go.com")
  case object YASUHIRO extends SystemEmailAddress("yasuhiro@42go.com")
  case object ANDREW extends SystemEmailAddress("andrew@42go.com")

  def apply(email: String): SystemEmailAddress = email match {
    case TEAM.address => TEAM
    case SUPPORT.address => SUPPORT
    case ENG.address => ENG
    case EISHAY.address => EISHAY
    case YASUHIRO.address => YASUHIRO
    case ANDREW.address => ANDREW
  }
}

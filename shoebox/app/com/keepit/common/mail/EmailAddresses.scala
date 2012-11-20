package com.keepit.common.mail
import com.keepit.model.EmailAddress

trait EmailAddressHolder {
  val address: String
}

sealed abstract class SystemEmailAddress(val address: String) extends EmailAddressHolder

object EmailAddresses {
  case object TEAM extends SystemEmailAddress("team@keepit.com")
  case object SUPPORT extends SystemEmailAddress("support@keepit.com")
  case object ENG extends SystemEmailAddress("eng@keepit.com")
  case object EISHAY extends SystemEmailAddress("eishay@keepit.com")
  case object YASUHIRO extends SystemEmailAddress("yasuhiro@keepit.com")
  case object ANDREW extends SystemEmailAddress("andrew@keepit.com")

  def apply(email: String): SystemEmailAddress = email match {
    case TEAM.address => TEAM
    case SUPPORT.address => SUPPORT
    case ENG.address => ENG
    case EISHAY.address => EISHAY
    case YASUHIRO.address => YASUHIRO
    case ANDREW.address => ANDREW
  }
}

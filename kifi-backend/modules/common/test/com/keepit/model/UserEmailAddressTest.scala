package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.common.mail.EmailAddress

class UserEmailAddressTest extends Specification {

  "Email Address" should {
    "know when its fake" in {
      UserEmailAddress(userId = null, address = EmailAddress("eishay@gmail.com")).isTestEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test@gmail.com")).isTestEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay@42go.com")).isTestEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishaytest@42go.com")).isTestEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("test@42go.com")).isTestEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test1@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+testandmore@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+testandmore@42go.com")).isTestEmail() === true
    }
    "know when it's auto-generated" in {
      UserEmailAddress(userId = null, address = EmailAddress("eishay@gmail.com")).isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test@gmail.com")).isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay@42go.com")).isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishaytest@42go.com")).isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+test@42go.com")).isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen@42go.com")).isAutoGenEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen123@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen123@42go.com")).isAutoGenEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen_123@42go.com")).isTestEmail() === true
      UserEmailAddress(userId = null, address = EmailAddress("eishay+autogen_123@42go.com")).isAutoGenEmail() === true
    }
  }
}

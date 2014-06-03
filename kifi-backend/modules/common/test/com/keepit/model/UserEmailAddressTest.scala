package com.keepit.model

import org.specs2.mutable.Specification

class UserEmailAddressTest extends Specification {

  "Email Address" should {
    "know when its fake" in {
      UserEmailAddress(userId = null, address = "eishay@gmail.com").isTestEmail() === false
      UserEmailAddress(userId = null, address = "eishay+test@gmail.com").isTestEmail() === false
      UserEmailAddress(userId = null, address = "eishay@42go.com").isTestEmail() === false
      UserEmailAddress(userId = null, address = "eishaytest@42go.com").isTestEmail() === false
      UserEmailAddress(userId = null, address = "eishay+test@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "test@42go.com").isTestEmail() === false
      UserEmailAddress(userId = null, address = "eishay+test1@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "eishay+testandmore@42go.com").isTestEmail() === true
    }
    "know when it's auto-generated" in {
      UserEmailAddress(userId = null, address = "eishay@gmail.com").isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = "eishay+test@gmail.com").isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = "eishay@42go.com").isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = "eishaytest@42go.com").isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = "eishay+test@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "eishay+test@42go.com").isAutoGenEmail() === false
      UserEmailAddress(userId = null, address = "eishay+autogen@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "eishay+autogen@42go.com").isAutoGenEmail() === true
      UserEmailAddress(userId = null, address = "eishay+autogen123@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "eishay+autogen123@42go.com").isAutoGenEmail() === true
      UserEmailAddress(userId = null, address = "eishay+autogen_123@42go.com").isTestEmail() === true
      UserEmailAddress(userId = null, address = "eishay+autogen_123@42go.com").isAutoGenEmail() === true
    }
  }
}

package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.common.mail.EmailAddress

class UserEmailAddressTest extends Specification {

  "UserEmailAddress" should {
    "know when it's a test account" in {
      uea("eishay@gmail.com").isTest === false
      uea("eishay+test@gmail.com").isTest === false
      uea("eishay@42go.com").isTest === false
      uea("eishaytest@42go.com").isTest === false
      uea("eishay+test@42go.com").isTest === true
      uea("test@42go.com").isTest === false
      uea("eishay+test1@42go.com").isTest === true
      uea("eishay+testandmore@42go.com").isTest === true
      uea("eishay+test2+more@42go.com").isTest === true
      uea("eishay+autogen@42go.com").isTest === false
      uea("a+preview+test2@kifi.com").isTest === true
      uea("a+test2+preview@kifi.com").isTest === true
    }
    "know when it's auto-generated" in {
      uea("eishay@gmail.com").isAutoGen === false
      uea("eishay+test@gmail.com").isAutoGen === false
      uea("eishay@42go.com").isAutoGen === false
      uea("eishaytest@42go.com").isAutoGen === false
      uea("eishay+test@42go.com").isAutoGen === false
      uea("eishay+autogen@gmail.com").isAutoGen === false
      uea("eishay+autogen@42go.com").isAutoGen === true
      uea("eishay+autogen123@42go.com").isAutoGen === true
      uea("eishay+autogen_123@42go.com").isAutoGen === true
    }
    "allow anyone to access kifi black with +preview tag" in {
      UserEmailAddress.getExperiments(uea("a@b")) === Set.empty
      UserEmailAddress.getExperiments(uea("a+preview@b")) === Set(ExperimentType.KIFI_BLACK)
      UserEmailAddress.getExperiments(uea("a+preview2@b")) === Set.empty
      UserEmailAddress.getExperiments(uea("a+preview+test2@kifi.com")) === Set(ExperimentType.FAKE, ExperimentType.KIFI_BLACK)
      UserEmailAddress.getExperiments(uea("a+test2+preview@kifi.com")) === Set(ExperimentType.FAKE, ExperimentType.KIFI_BLACK)
    }
  }

  private def uea(addr: String): UserEmailAddress = UserEmailAddress(userId = null, address = EmailAddress(addr))
}

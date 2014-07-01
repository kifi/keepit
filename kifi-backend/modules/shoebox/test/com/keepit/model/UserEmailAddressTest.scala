package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.common.mail.EmailAddress
import com.keepit.model.ExperimentType.{AUTO_GEN, FAKE, GUIDE, KIFI_BLACK}

class UserEmailAddressTest extends Specification {

  "UserEmailAddress" should {
    "assign experiments" in {
      exp("eishay@gmail.com") === Set.empty
      exp("eishay+test@gmail.com") === Set.empty
      exp("eishay@42go.com") === Set.empty
      exp("eishaytest@42go.com") === Set.empty
      exp("eishay+test@42go.com") === Set(FAKE)
      exp("test@42go.com") === Set.empty
      exp("eishay+test1@42go.com") === Set(FAKE)
      exp("eishay+testandmore@42go.com") === Set(FAKE)
      exp("eishay+test2+more@42go.com") === Set(FAKE)
      exp("eishay+autogen@42go.com") === Set(FAKE, AUTO_GEN)
      exp("a+preview+test2@kifi.com") === Set(FAKE, KIFI_BLACK)
      exp("a+test2+preview@kifi.com") === Set(FAKE, KIFI_BLACK)
      exp("eishay+autogen@gmail.com") === Set.empty
      exp("eishay+autogen@42go.com") === Set(FAKE, AUTO_GEN)
      exp("eishay+autogen123@42go.com") === Set(FAKE, AUTO_GEN)
      exp("eishay+autogen_123@42go.com") === Set(FAKE, AUTO_GEN)
      exp("a+preview@b") === Set(KIFI_BLACK, GUIDE)
      exp("a+preview2@b") === Set.empty
      exp("a+preview+test2@kifi.com") === Set(FAKE, KIFI_BLACK)
      exp("a+test2+preview@kifi.com") === Set(FAKE, KIFI_BLACK)
      exp("a+autogen2+preview@kifi.com") === Set(FAKE, KIFI_BLACK, AUTO_GEN)
    }
  }

  private def uea(addr: String) = UserEmailAddress(userId = null, address = EmailAddress(addr))
  private def exp(addr: String) = UserEmailAddress.getExperiments(uea(addr))
}

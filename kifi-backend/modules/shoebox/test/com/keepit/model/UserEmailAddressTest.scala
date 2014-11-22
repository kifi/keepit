package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.common.mail.EmailAddress
import com.keepit.model.ExperimentType.{ AUTO_GEN, FAKE }

class UserEmailAddressTest extends Specification {

  "UserEmailAddress" should {
    "assign experiments" in {
      exp("eishay@gmail.com") === Set.empty
      exp("eishay+test@gmail.com") === Set.empty
      exp("eishay@42go.com") === Set.empty
      exp("eishaytest@42go.com") === Set.empty
      exp("eishay+test@42go.com") === Set(FAKE)
      exp("pqccmsg_yangescu_1416509875@tfbnw.net") === Set(FAKE)
      exp("foo@mailinator.com") === Set(FAKE)
      exp("test@42go.com") === Set.empty
      exp("eishay+test1@42go.com") === Set(FAKE)
      exp("eishay+testandmore@42go.com") === Set(FAKE)
      exp("eishay+test2+more@42go.com") === Set(FAKE)
      exp("eishay+autogen@42go.com") === Set(FAKE, AUTO_GEN)
      exp("eishay+autogen@gmail.com") === Set.empty
      exp("eishay+autogen@42go.com") === Set(FAKE, AUTO_GEN)
      exp("eishay+autogen123@42go.com") === Set(FAKE, AUTO_GEN)
      exp("eishay+autogen_123+more@42go.com") === Set(FAKE, AUTO_GEN)
    }
  }

  private def uea(addr: String) = UserEmailAddress(userId = null, address = EmailAddress(addr))
  private def exp(addr: String) = UserEmailAddress.getExperiments(uea(addr))
}

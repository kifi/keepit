package com.keepit.commanders.emails

import org.specs2.mutable.Specification
import com.keepit.test.ShoeboxTestInjector
import com.keepit.common.mail.{EmailAddress, TestMailModule}

class EmailOptOutCommanderTest extends Specification with ShoeboxTestInjector {

  "EmailOptOutCommander" should {
    "generate opt-out token" in {
      withInjector(TestMailModule()) { implicit injector =>
        val commander = inject[EmailOptOutCommander]
        val addresses = Seq("1f07d01@emailtests.com", "chkemltests@gapps.emailtests.com")
        addresses map { address =>
          val email = EmailAddress(address)
          val token = commander.generateOptOutToken(email)
          val retrievedEmail = commander.getEmailFromOptOutToken(token)
          email.address === retrievedEmail.get.address
        }
      }
    }
  }

}

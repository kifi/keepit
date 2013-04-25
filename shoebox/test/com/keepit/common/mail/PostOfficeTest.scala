package com.keepit.common.mail

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification

class PostOfficeTest extends Specification {

  "PostOffice" should {
    "persist and load email" in {
      running(new ShoeboxApplication().withFakeMail()) {
        val mail1 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 1", htmlBody = "some body in html 1", category = PostOffice.Categories.HEALTHCHECK))
        val outbox = inject[FakeOutbox]
        outbox.size === 1
        outbox(0).externalId === mail1.externalId
        outbox(0).htmlBody === mail1.htmlBody
        val mail2 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "some body in html 2", category = PostOffice.Categories.HEALTHCHECK))
        outbox.size === 2
        outbox(1).externalId === mail2.externalId
        outbox(0).htmlBody === mail1.htmlBody
        outbox(1).htmlBody === mail2.htmlBody
      }
    }
  }
}

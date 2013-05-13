package com.keepit.common.mail

import org.specs2.mutable.Specification

import com.keepit.test._

class PostOfficeTest extends Specification with TestDBRunner {

  "PostOffice" should {
    "persist and load email" in {
      withDB(FakeMailModule()) { implicit injector =>
        val (mail1, outbox) = db.readWrite { implicit s =>
          val mail1 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.TEAM), subject = "foo 1", htmlBody = "some body in html 1", category = PostOffice.Categories.HEALTHCHECK))
          (mail1, inject[FakeOutbox])
        }
        outbox.size === 1
        outbox(0).id.get === mail1.id.get
        outbox(0).htmlBody === mail1.htmlBody
        val mail2 = db.readWrite { implicit s =>
          inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = List(EmailAddresses.TEAM), subject = "foo 2", htmlBody = "some body in html 2", category = PostOffice.Categories.HEALTHCHECK))
        }
        outbox.size === 2
        outbox(1).id.get === mail2.id.get
        outbox(0).htmlBody === mail1.htmlBody
        outbox(1).htmlBody === mail2.htmlBody
      }
    }
  }
}

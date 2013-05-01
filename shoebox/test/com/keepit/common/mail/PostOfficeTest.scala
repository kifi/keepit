package com.keepit.common.mail

import org.specs2.mutable.Specification

import com.keepit.common.db.slick.Database
import com.keepit.inject._
import com.keepit.test._

import play.api.Play.current
import play.api.test.Helpers._

class PostOfficeTest extends Specification with TestDBRunner {

  "PostOffice" should {
    "persist and load email" in {
      withDB(FakeMailModule()) { implicit injector =>
        val (mail1, outbox) = db.readWrite { implicit s =>
          val mail1 = inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 1", htmlBody = "some body in html 1", category = PostOffice.Categories.HEALTHCHECK))
          (mail1, inject[FakeOutbox])
        }
        db.readOnly { implicit s =>
          outbox.size === 1
          outbox(0) === mail1.id.get
          electronicMailRepo.get(outbox(0)).htmlBody === mail1.htmlBody
        }
        val mail2 = db.readWrite { implicit s =>
          inject[PostOffice].sendMail(ElectronicMail(from = EmailAddresses.ENG, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "some body in html 2", category = PostOffice.Categories.HEALTHCHECK))
        }
        db.readOnly { implicit s =>
          outbox.size === 2
          outbox(1) === mail2.id.get
          electronicMailRepo.get(outbox(0)).htmlBody === mail1.htmlBody
          electronicMailRepo.get(outbox(1)).htmlBody === mail2.htmlBody
        }
      }
    }
  }
}

package com.keepit.common.mail

import org.specs2.mutable.Specification

import com.keepit.test._
import com.keepit.model.NotificationCategory

class PostOfficeTest extends Specification with ShoeboxTestInjector {

  "LocalPostOffice" should {
    "persist and load email" in {
      withDb(FakeMailModule()) { implicit injector =>
        val (mail1, outbox) = db.readWrite { implicit s =>
          val mail1 = inject[LocalPostOffice].sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.TEAM), subject = "foo 1", htmlBody = "some body in html 1", category = NotificationCategory.System.HEALTHCHECK))
          (mail1, inject[FakeOutbox])
        }
        outbox.size === 1
        outbox(0).id.get === mail1.id.get
        outbox(0).htmlBody === mail1.htmlBody
        val mail2 = db.readWrite { implicit s =>
          inject[LocalPostOffice].sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = List(SystemEmailAddress.TEAM), subject = "foo 2", htmlBody = "some body in html 2", category = NotificationCategory.System.HEALTHCHECK))
        }
        outbox.size === 2
        outbox(1).id.get === mail2.id.get
        outbox(0).htmlBody === mail1.htmlBody
        outbox(1).htmlBody === mail2.htmlBody
      }
    }
  }
}

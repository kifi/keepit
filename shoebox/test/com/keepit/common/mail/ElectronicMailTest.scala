package com.keepit.common.mail

import com.keepit.test._
import com.keepit.inject._
import play.api.Play.current
import play.api.libs.json.JsValue
import play.api.test.Helpers._
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import org.specs2.mutable.Specification
import com.keepit.common.db._
import com.keepit.common.db.slick._
import com.keepit.common.db.slick.DBSession._


class ElectronicMailTest extends Specification with TestDBRunner {

  "ElectronicMail" should {
    "user filters" in {
      withDB(FakeMailModule()) { implicit injector =>
        val mails = db.readWrite { implicit s =>
          val mails = ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.ENG, subject = "foo 1", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK) ::
                      ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.TEAM, subject = "foo 2", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK) ::
                      ElectronicMail(from = EmailAddresses.TEAM, to = EmailAddresses.EISHAY, subject = "foo 3", htmlBody = "body", category = PostOffice.Categories.HEALTHCHECK) ::
                      Nil
          mails map {mail => electronicMailRepo.save(mail) }
        }
        db.readOnly { implicit s =>
          electronicMailRepo.page(0, 10, EmailAddresses.ENG).size == 2
          electronicMailRepo.page(0, 2, EmailAddresses.ENG).size == 2
          electronicMailRepo.count(EmailAddresses.ANDREW) === 3
          electronicMailRepo.count(EmailAddresses.ENG) === 2
          mails foreach { mail =>
            electronicMailRepo.get(mail.id.get) === mail
          }
        }
      }
    }
  }
}

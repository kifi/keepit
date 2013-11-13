package com.keepit.common.mail

import com.keepit.test._
import org.specs2.mutable.Specification

class ElectronicMailTest extends Specification with ShoeboxTestInjector {

  "ElectronicMail" should {
    "long title" in {
      ElectronicMail(from = EmailAddresses.TEAM,
        to = List(EmailAddresses.ENG),
        subject = new StringBuilder("foo") * 500,
        htmlBody = "body",
        category = PostOffice.Categories.System.HEALTHCHECK) must throwA[IllegalArgumentException]
    }
    "user filters" in {
      withDb(FakeMailModule()) { implicit injector =>
        val mails = db.readWrite { implicit s =>
          val mails = ElectronicMail(from = EmailAddresses.TEAM, to = List(EmailAddresses.ENG), subject = "foo 1", htmlBody = "body", category = PostOffice.Categories.System.HEALTHCHECK) ::
                      ElectronicMail(from = EmailAddresses.TEAM, to = List(EmailAddresses.TEAM), cc = EmailAddresses.EISHAY :: EmailAddresses.JARED :: Nil, subject = "foo 2", htmlBody = "body 2", textBody = Some("other"), category = PostOffice.Categories.System.HEALTHCHECK) ::
                      ElectronicMail(from = EmailAddresses.TEAM, to = List(EmailAddresses.EISHAY), subject = "foo 3", htmlBody = "body", category = PostOffice.Categories.System.HEALTHCHECK) ::
                      Nil
          mails map {mail => electronicMailRepo.save(mail) }
        }
        db.readOnly { implicit s =>
          electronicMailRepo.page(0, 10).size == 3
          electronicMailRepo.page(0, 2).size == 3
          mails foreach { mail =>
            electronicMailRepo.get(mail.id.get) === mail
          }
        }
      }
    }
  }
}

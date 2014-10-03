package com.keepit.common.mail

import com.keepit.common.mail.template.{ TemplateOptions, EmailLayout, EmailToSend }
import com.keepit.heimdal.ContextData
import com.keepit.test._
import org.specs2.mutable.Specification
import com.keepit.model.NotificationCategory
import play.api.libs.json.{ JsValue, JsSuccess, Json }
import play.twirl.api.Html

class ElectronicMailTest extends Specification with ShoeboxTestInjector {

  "ElectronicMail" should {
    "long title" in {
      ElectronicMail(from = SystemEmailAddress.TEAM,
        to = List(SystemEmailAddress.ENG),
        subject = new StringBuilder("foo") * 500,
        htmlBody = "body",
        category = NotificationCategory.System.HEALTHCHECK) must throwA[IllegalArgumentException]
    }
    "user filters" in {
      withDb(FakeMailModule()) { implicit injector =>
        val mails = db.readWrite { implicit s =>
          val mails = ElectronicMail(from = SystemEmailAddress.TEAM, to = Seq(SystemEmailAddress.ENG), subject = "foo 1", htmlBody = "body", category = NotificationCategory.System.HEALTHCHECK) ::
            ElectronicMail(from = SystemEmailAddress.TEAM, to = Seq(SystemEmailAddress.TEAM), cc = Seq(SystemEmailAddress.EISHAY, SystemEmailAddress.JARED), subject = "foo 2", htmlBody = "body 2", textBody = Some("other"), category = NotificationCategory.System.HEALTHCHECK) ::
            ElectronicMail(from = SystemEmailAddress.TEAM, to = Seq(SystemEmailAddress.EISHAY), subject = "foo 3", htmlBody = "body", category = NotificationCategory.System.HEALTHCHECK) ::
            Nil
          mails map { mail => electronicMailRepo.save(mail) }
        }
        db.readOnlyMaster { implicit s =>
          electronicMailRepo.page(0, 10).size === 3
          electronicMailRepo.page(0, 2).size === 3
          mails foreach { mail =>
            electronicMailRepo.get(mail.id.get) === mail
          }
        }
      }
    }

    "EmailToSend is JSONable" in {
      val em = EmailToSend(
        from = SystemEmailAddress.ENG,
        to = Right(SystemEmailAddress.JOSH),
        subject = "Test",
        campaign = Some("testing"),
        category = NotificationCategory.User.DIGEST,
        htmlTemplate = Html("this is <b>html</b>"),
        closingLines = Seq("Happy Keeping!", "The Kifi team")
      )

      val otherEm = Json.fromJson[EmailToSend](Json.toJson[EmailToSend](em)).get

      // Html objects don't have a good equals method when we just care about the Html.body
      val blankHtml = Html("")
      otherEm.copy(htmlTemplate = blankHtml) === em.copy(htmlTemplate = blankHtml)
      otherEm.htmlTemplate.body === em.htmlTemplate.body
    }
  }
}

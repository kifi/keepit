package com.keepit.common.mail

import com.keepit.common.mail.template.EmailToSend
import com.keepit.test._
import org.specs2.mutable.Specification
import com.keepit.model.NotificationCategory
import play.api.libs.json.{ JsSuccess, Json }
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
        htmlTemplate = Html("this is <b>html</b>")
      )

      val expectedJson = """
          |{"title":"Kifi","from":"eng@42go.com","to":"josh@kifi.com","cc":[],"subject":"Test",
          |"htmlTemplate":"this is <b>html</b>","category":"digest",
          |"campaign":"testing","tips":[]}
        """.stripMargin
      val jsVal = Json.parse(expectedJson)
      Json.toJson(em) === jsVal

      val result = jsVal.as[EmailToSend]
      result.from === em.from
      result.to === em.to
      result.subject === em.subject
      result.campaign === em.campaign
      result.category === em.category
      result.htmlTemplate.body === em.htmlTemplate.body
    }
  }
}

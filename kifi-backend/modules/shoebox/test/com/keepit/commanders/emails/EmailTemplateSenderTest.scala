package com.keepit.commanders.emails

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.inject.FortyTwoConfig
import com.keepit.model.NotificationCategory
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.templates.Html

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EmailTemplateSenderTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeMailModule(),
    FakeHttpClientModule(),
    ProdShoeboxServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeABookServiceClientModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCuratorServiceClientModule()
  )

  "EmailTemplateSenderCommander" should {
    "send email" in {
      import com.keepit.model.Email.placeholders._
      withDb(modules: _*) { implicit injector =>
        val testFactory = inject[ShoeboxTestFactory]
        val (user1, user2, user3, user4) = db.readWrite { implicit rw =>
          testFactory.createUsers()
        }
        val (id1, id2, id3, id4) = (user1.id.get, user2.id.get, user3.id.get, user4.id.get)

        val html1 = Html("Hello, " + fullName(id2))
        val html2 = Html("Image: " + avatarUrl(id1))
        val commander = inject[EmailTemplateSender]
        val config = inject[FortyTwoConfig]
        val emailRepo = inject[ElectronicMailRepo]

        val emailToSend = EmailToSend(
          to = Left(id3),
          cc = Seq(SystemEmailAddress.ENG),
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some("Kifi"),
          subject = "hi",
          category = NotificationCategory.System.ADMIN,
          htmlTemplates = Seq(html1, html2)
        )

        val emailF = commander.send(emailToSend)
        val email = Await.result(emailF, Duration(5, "seconds"))

        db.readOnlyMaster { implicit s =>
          val freshEmail = emailRepo.get(email.id.get)
          freshEmail === email
          email.subject === "hi"
          email.to === Seq(EmailAddress("test@gmail.com"))
          email.cc === Seq(SystemEmailAddress.ENG)
          email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.System.ADMIN)
          val html = email.htmlBody.toString()
          html must contain("Hello, Bryan Cranston")
          html must contain("Image: http://cloudfront/1_100x100_0")
        }
      }
    }
  }
}

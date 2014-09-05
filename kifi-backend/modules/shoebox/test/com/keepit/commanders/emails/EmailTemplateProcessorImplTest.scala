package com.keepit.commanders.emails

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ SystemEmailAddress, EmailToSend }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.NotificationCategory
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.templates.Html
import com.keepit.model.Email.placeholders._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class EmailTemplateProcessorImplTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
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

  "EmailTemplateProcessor" should {
    "replaces placeholders with real values" in {
      withDb(modules: _*) { implicit injector =>
        val testFactory = inject[ShoeboxTestFactory]
        val (user1, user2, user3, user4) = db.readWrite { implicit rw =>
          testFactory.createUsers()
        }
        val (id1, id2, id3, id4) = (user1.id.get, user2.id.get, user3.id.get, user4.id.get)

        val html1 = Html(s"""
          |${firstName(id1)} ${lastName(id1)} and ${fullName(id2)} joined!
          |<img src="${avatarUrl(id1)}" alt="${fullName(id1)}"/>
          |<img src="${avatarUrl(id2)}" alt="${fullName(id2)}"/>
          |<img src="${avatarUrl(id3)}" alt="${fullName(id3)}"/>
          |<img src="${avatarUrl(id4)}" alt="${fullName(id4)}"/>
          |<a href="$unsubscribeUrl">Unsubscribe Me</a>
          |<a href="${unsubscribeUrl(id3)}">Unsubscribe User</a>
          |<a href="${unsubscribeUrl(user3.primaryEmail.get)}">Unsubscribe Email</a>
        """.stripMargin)
        val html2 = Html("")

        val processor = inject[EmailTemplateProcessorImpl]
        val emailToSend = EmailToSend(
          to = Right(SystemEmailAddress.JOSH),
          cc = Seq(SystemEmailAddress.ENG),
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some("Kifi"),
          subject = "hi",
          category = NotificationCategory.System.ADMIN,
          htmlTemplates = Seq(html1, html2)
        )

        val outputF = processor.process(emailToSend)
        val output = Await.result(outputF, Duration(5, "seconds")).body

        output must contain("Aaron Paul and Bryan Cranston joined!")
        output must contain("""<img src="http://cloudfront/1_100x100_0" alt="Aaron Paul"/>""")
        output must contain("""<img src="http://cloudfront/2_100x100_0" alt="Bryan Cranston"/>""")
        output must contain("""<img src="http://cloudfront/3_100x100_0" alt="Anna Gunn"/>""")
        output must contain("""<img src="http://cloudfront/4_100x100_0" alt="Dean Norris"/>""")
        output must be matching """(\s|.)*<a href="https://www.kifi.com/unsubscribe/[^"]+">Unsubscribe User(\s|.)*"""
        output must be matching """(\s|.)*<a href="https://www.kifi.com/unsubscribe/[^"]+">Unsubscribe Email(\s|.)*"""
      }
    }
  }
}

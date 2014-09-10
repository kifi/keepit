package com.keepit.commanders.emails

import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
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
import play.twirl.api.Html
import com.keepit.common.mail.template.helpers._

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

        val processor = inject[EmailTemplateProcessorImpl]
        val emailToSend = EmailToSend(
          title = "Test Email!!!",
          campaign = Some("tester"),
          to = Right(SystemEmailAddress.JOSH),
          cc = Seq(SystemEmailAddress.ENG),
          from = SystemEmailAddress.NOTIFICATIONS,
          fromName = Some("Kifi"),
          subject = "hi",
          category = NotificationCategory.System.ADMIN,
          htmlTemplate = html1
        )

        val outputF = processor.process(emailToSend)
        val output = Await.result(outputF, Duration(5, "seconds")).body

        output must contain("privacy?utm_source=footerPrivacy&utm_medium=email&utm_campaign=tester")
        output must contain("<title>Test Email!!!</title>")
        output must contain("Aaron Paul and Bryan Cranston joined!")
        output must contain("""<img src="http://cloudfront/users/1/pics/100/0.jpg" alt="Aaron Paul"/>""")
        output must contain("""<img src="http://cloudfront/users/2/pics/100/0.jpg" alt="Bryan Cranston"/>""")
        output must contain("""<img src="http://cloudfront/users/3/pics/100/0.jpg" alt="Anna Gunn"/>""")
        output must contain("""<img src="http://cloudfront/users/4/pics/100/0.jpg" alt="Dean Norris"/>""")
      }
    }
  }
}

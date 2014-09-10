package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.db.Id
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail._
import com.keepit.common.mail.template.{ EmailTips, EmailToSend }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ User, NotificationCategory }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.twirl.api.Html

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
    import com.keepit.common.mail.template.helpers.fullName

    def sendAndTestEmail(tips: Seq[EmailTips] = Seq.empty)(implicit injector: Injector) = {
      val testFactory = inject[ShoeboxTestFactory]
      val (user1, user2, user3, user4) = db.readWrite { implicit rw =>
        testFactory.createUsers()
      }
      val (id1, id2, id3, id4) = (user1.id.get, user2.id.get, user3.id.get, user4.id.get)

      val html = Html("Hello, " + fullName(id2))
      val commander = inject[EmailTemplateSender]
      val emailRepo = inject[ElectronicMailRepo]

      val emailToSend = EmailToSend(
        title = "Testing!!!",
        to = Left(id3),
        cc = Seq(SystemEmailAddress.ENG),
        from = SystemEmailAddress.NOTIFICATIONS,
        fromName = Some("Kifi"),
        subject = "hi",
        category = NotificationCategory.System.ADMIN,
        htmlTemplate = html,
        campaign = Some("testing"),
        tips = tips
      )

      val emailF = commander.send(emailToSend)
      val email = Await.result(emailF, Duration(5, "seconds"))

      db.readOnlyMaster { implicit s =>
        val freshEmail = emailRepo.get(email.id.get)
        freshEmail === email
        email.subject === "hi"
        email.to === Seq(EmailAddress("test@gmail.com"))
        email.cc === Seq(SystemEmailAddress.ENG)
        email.from === emailToSend.from
        email.fromName === emailToSend.fromName
        email.category === NotificationCategory.toElectronicMailCategory(NotificationCategory.System.ADMIN)
        val html = email.htmlBody.value
        html must contain("<title>Testing!!!</title>")
        html must contain("Hello, Bryan Cranston")
      }
    }

    "send the email" in {
      withDb(modules: _*) { implicit injector => sendAndTestEmail() }
    }

    "send the email with PYMK tip" in {
      withDb(modules: _*) { implicit injector =>
        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abook.addFriendRecommendationsExpectations(Id[User](3), Seq(1L, 2L, 4L).map(id => Id[User](id)))

        val tips = Seq(EmailTips.FriendRecommendations)
        sendAndTestEmail(tips)

        db.readOnlyMaster { implicit s =>
          val email = inject[ElectronicMailRepo].all().head
          val html = email.htmlBody.value
          html must contain("Find friends on Kifi to benefit from their keeps")
          html must contain("http://cloudfront/users/1/pics/100/0.jpg")
          html must contain("alt=\"Aaron\"")
          html must contain("http://cloudfront/users/2/pics/100/0.jpg")
          html must contain("alt=\"Bryan\"")
          html must contain("http://cloudfront/users/4/pics/100/0.jpg")
          html must contain("alt=\"Dean\"")
        }
      }
    }
  }
}

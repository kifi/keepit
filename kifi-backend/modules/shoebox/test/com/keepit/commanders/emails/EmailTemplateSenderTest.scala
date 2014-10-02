package com.keepit.commanders.emails

import com.google.inject.Injector
import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.db.Id
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail._
import com.keepit.common.mail.template.{ EmailTip, EmailToSend }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ UserValueName, UserValueRepo, User, NotificationCategory }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.ProdShoeboxServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.{ Json, JsArray }
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

    def sendAndTestEmail(tips: Seq[EmailTip] = Seq.empty)(implicit injector: Injector) = {
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
        fromName = Some(Left(id1)),
        subject = "hi from " + fullName(id1),
        category = NotificationCategory.System.ADMIN,
        htmlTemplate = html,
        campaign = Some("testing"),
        tips = tips
      )

      val userValueRepo = inject[UserValueRepo]
      db.readOnlyMaster { implicit s =>
        userValueRepo.getUserValue(id3, UserValueName.LATEST_EMAIL_TIPS_SENT) must beNone

        val emailF = commander.send(emailToSend)
        val email = Await.result(emailF, Duration(5, "seconds"))

        val expectedUserValue = {
          val jsValue = Json.toJson(tips)
          Json.stringify(jsValue)
        }

        val userValue = userValueRepo.getUserValue(id3, UserValueName.LATEST_EMAIL_TIPS_SENT)
        if (tips.nonEmpty) userValue.get.value === expectedUserValue
        else userValue must beNone

        val freshEmail = emailRepo.get(email.id.get)
        freshEmail === email
        email.subject === "hi from Aaron Paul"
        email.to === Seq(EmailAddress("test@gmail.com"))
        email.cc === Seq(SystemEmailAddress.ENG)
        email.from === SystemEmailAddress.NOTIFICATIONS
        email.fromName === Some("Aaron Paul (via Kifi)")
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

        val tips = Seq(EmailTip.FriendRecommendations)
        sendAndTestEmail(tips)

        db.readOnlyMaster { implicit s =>
          val email = inject[ElectronicMailRepo].all().head
          val html = email.htmlBody.value
          html must contain("Find friends on Kifi to benefit from their keeps")
          html must contain("https://cloudfront/users/1/pics/100/0.jpg")
          html must contain("alt=\"Aaron\"")
          html must contain("https://cloudfront/users/2/pics/100/0.jpg")
          html must contain("alt=\"Bryan\"")
          html must contain("https://cloudfront/users/4/pics/100/0.jpg")
          html must contain("alt=\"Dean\"")
        }
      }
    }
  }
}

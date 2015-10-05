package com.keepit.commanders.emails.tips

import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.commanders.emails.FriendRecommendationsEmailTip
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model._
import com.keepit.model.UserFactoryHelper._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.twirl.api.Html

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class FriendRecommendationsEmailTipTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "PeopleRecommendationsTip" should {

    "returns email HTML for people you may know" in {
      withDb(modules: _*) { implicit injector =>
        val tip = inject[FriendRecommendationsEmailTip]
        val factory = inject[ShoeboxTestFactory]
        val (user1, user2, user3, user4) = db.readWrite { implicit rw => factory.createUsers() }

        val friends = db.readWrite { implicit rw =>
          Seq(
            UserFactory.user().withName("Bob", "Marley").withUsername("test1").withPictureName("0").saved,
            UserFactory.user().withName("Joe", "Mustache").withUsername("test2").withPictureName("mustache").saved,
            UserFactory.user().withName("Mr", "T").withUsername("test3").withPictureName("mrt").saved,
            UserFactory.user().withName("Dolly", "Parton").withUsername("test4").withPictureName("dolly").saved
          )
        }
        val friendIds = friends.map(_.id.get)

        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        abook.addFriendRecommendationsExpectations(user1.id.get, friendIds)
        abook.addFriendRecommendationsExpectations(user2.id.get, friendIds)

        val toUserId = user1.id.get
        val emailToSend = EmailToSend(
          title = "Testing!!!",
          to = Left(toUserId),
          cc = Seq(SystemEmailAddress.ENG),
          from = SystemEmailAddress.NOTIFICATIONS,
          subject = "hi",
          category = NotificationCategory.System.ADMIN,
          htmlTemplate = Html("")
        )
        val htmlOptF: Future[Option[Html]] = tip.render(emailToSend, toUserId)
        val html = Await.result(htmlOptF, Duration(5, "seconds")).get.body

        // Friend Recommendations
        friends.slice(1, 4).foreach { user =>
          html must contain(s"""<%kf% ["profileUrl",${user.id.get}] %kf%>?intent=connect""")
        }
        // b/c user doesn't have an avatar
        html must not contain friends(0).externalId.toString
      }
    }

  }

}

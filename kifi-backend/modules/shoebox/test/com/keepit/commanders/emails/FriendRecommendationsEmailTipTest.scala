package com.keepit.commanders.emails

import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NotificationCategory, UserRepo, User }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.{ ShoeboxTestFactory, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.twirl.api.Html

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class FriendRecommendationsEmailTipTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeScrapeSchedulerModule(),
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
        val userRepo = inject[UserRepo]
        val factory = inject[ShoeboxTestFactory]
        val (user1, user2, user3, user4) = db.readWrite { implicit rw => factory.createUsers() }

        val friends = db.readWrite { implicit rw =>
          Seq(
            userRepo.save(User(firstName = "Bob", lastName = "Marley", pictureName = Some("0"))),
            userRepo.save(User(firstName = "Joe", lastName = "Mustache", pictureName = Some("mustache"))),
            userRepo.save(User(firstName = "Mr", lastName = "T", pictureName = Some("mrt"))),
            userRepo.save(User(firstName = "Dolly", lastName = "Parton", pictureName = Some("dolly"))),
            userRepo.save(User(firstName = "Benedict", lastName = "Arnold", pictureName = Some("benedict"))),
            userRepo.save(User(firstName = "Winston", lastName = "Churchill", pictureName = Some("winston")))
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
          fromName = Some("Kifi"),
          subject = "hi",
          category = NotificationCategory.System.ADMIN,
          htmlTemplate = Html("")
        )
        val htmlOptF: Future[Option[Html]] = tip.render(emailToSend)
        val html = Await.result(htmlOptF, Duration(5, "seconds")).get.body

        // Friend Recommendations
        friends.slice(1, 5).foreach { user =>
          html must contain(s"""?friend=<%kf% ["userExternalId",${user.id.get}] %kf%>&subtype=digestPymk""")

          // check user avatar urls
          html must contain(s"cloudfront/users/${user.id.get}/pics/100/${user.pictureName.get}.jpg")
        }
        html must not contain friends(0).externalId.toString
        html must not contain s"cloudfront/users/${friends(0).id.get}/pics/100/${friends(0).pictureName.get}.jpg"
      }
    }

  }

}

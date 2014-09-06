package com.keepit.curator

import com.keepit.abook.{ ABookServiceClient, FakeABookServiceClientImpl, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.{ DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.email.{ PeopleRecommendationsTip, DigestRecoMail, FeedDigestEmailSender }
import com.keepit.curator.model.{ SeedAttribution, TopicAttribution, UriRecommendationRepo, UserAttribution }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialId, SocialNetworks }
import org.specs2.mutable.Specification
import play.api.templates.Html

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class PeopleRecommendationsTipTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  val modules = Seq(
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "PeopleRecommendationsTip" should {

    "returns email HTML for people you may know" in {
      withDb(modules: _*) { implicit injector =>
        val shoebox = shoeboxClientInstance()
        val tip = inject[PeopleRecommendationsTip]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)
        val uriRecoRepo = inject[UriRecommendationRepo]

        shoebox.socialUserInfosByUserId(user1.id.get) = List()
        shoebox.socialUserInfosByUserId(user2.id.get) = List(SocialUserInfo(fullName = "Muggsy Bogues", profileUrl = Some("http://fb.com/me"), networkType = SocialNetworks.FACEBOOK, socialId = SocialId("123")))

        val friend1 = User(id = Some(Id[User](44)), firstName = "Joe", lastName = "Mustache",
          pictureName = Some("mustache"))
        val friend2 = User(id = Some(Id[User](45)), firstName = "Mr", lastName = "T",
          pictureName = Some("mrt"))
        val friend3 = User(id = Some(Id[User](46)), firstName = "Dolly", lastName = "Parton",
          pictureName = Some("mrt"))
        val friend4 = User(id = Some(Id[User](47)), firstName = "Benedict", lastName = "Arnold",
          pictureName = Some("mrt"))
        val friend5 = User(id = Some(Id[User](48)), firstName = "Winston", lastName = "Churchill",
          pictureName = Some("mrt"))
        val friend6 = User(id = Some(Id[User](49)), firstName = "Bob", lastName = "Marley",
          pictureName = Some("0"))

        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        val friends = Seq(friend1, friend2, friend3, friend4, friend5, friend6)
        val friendIds = friends.map(_.id.get)
        abook.addFriendRecommendationsExpectations(user1.id.get, friendIds)
        abook.addFriendRecommendationsExpectations(user2.id.get, friendIds)

        shoebox.saveUsers(friends: _*)
        shoebox.saveUserImageUrl(44, "//url.com/u44.jpg")
        shoebox.saveUserImageUrl(48, "//url.com/u48.jpg")
        shoebox.saveUserImageUrl(49, "//url.com/0.jpg")

        val htmlF: Future[Html] = tip.getHtml(user1.id.get)
        val html = Await.result(htmlF, Duration(5, "seconds")).body

        // Friend Recommendations
        friends.slice(0, 4).foreach { user =>
          html must contain(s"""?friend=<%kf% ["userExternalId",${user.id.get}] %kf%>&subtype=digestPymk""")
        }
        html must not contain friends(5).externalId.toString

        // check user avatar urls
        html must contain("https://url.com/u44.jpg")
        html must contain("https://url.com/u48.jpg")
      }
    }

  }

}

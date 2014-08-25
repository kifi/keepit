package com.keepit.curator

import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.model.User
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import commanders.email.{ FeedDigestEmailSender, DigestRecoMail }
import com.keepit.curator.model.{ TopicAttribution, UserAttribution, SeedAttribution, UriRecommendationRepo }
import org.specs2.mutable.Specification
import com.keepit.heimdal.FakeHeimdalServiceClientModule

import concurrent.duration.Duration
import concurrent.{ Await, Future }

class FeedDigestEmailSenderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  val modules = Seq(
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeABookServiceClientModule())

  "FeedDigestEmailSender" should {

    "sends not-already-pushed keeps to users" in {
      withDb(modules: _*) { implicit injector =>
        val shoebox = shoeboxClientInstance()
        val sender = inject[FeedDigestEmailSender]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)
        val uriRecoRepo = inject[UriRecommendationRepo]

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

        val abook = inject[ABookServiceClient].asInstanceOf[FakeABookServiceClientImpl]
        val friends = Seq(friend1, friend2, friend3, friend4, friend5)
        val friendIds = friends.map(_.id.get)
        abook.addFriendRecommendationsExpectations(user1.id.get, friendIds)
        abook.addFriendRecommendationsExpectations(user2.id.get, friendIds)

        shoebox.saveUsers(friends: _*)

        val savedRecoModels = db.readWrite { implicit rw =>
          Seq(
            {
              val tuple = makeCompleteUriRecommendation(1, 42, 0.15f, "https://www.kifi.com", 10000)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1, friend2).map(_.id.get), others = 1)),
                topic = Some(TopicAttribution("Reading"))
              )))
            },
            {
              val tuple = makeCompleteUriRecommendation(2, 42, 0.99f, "https://www.google.com", 2500)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1.id.get), others = 2)),
                topic = Some(TopicAttribution("Searching"))
              )))
            },
            makeCompleteUriRecommendation(3, 43, 0.3f, "http://www.42go.com"),
            makeCompleteUriRecommendation(4, 43, 0.4f, "http://www.yahoo.com"),
            makeCompleteUriRecommendation(5, 43, 0.5f, "http://www.lycos.com", 250, Some(200)),
            {
              val tuple = makeCompleteUriRecommendation(6, 42, 0.99f, "http://www.excite.com")
              tuple.copy(_2 = tuple._2.withLastPushedAt(currentDateTime))
            }
          ).map(tuple => saveUriModels(tuple, shoebox))
        }

        val sendFuture: Future[Seq[DigestRecoMail]] = sender.send()
        val summaries = Await.result(sendFuture, Duration(5, "seconds"))

        summaries.size === 7
        val sumU42 = summaries.find(_.userId.id == 42).get
        val sumU43 = summaries.find(_.userId.id == 43).get

        sumU42.feed.size === 2

        // lycos and excite should not be included because:
        // - lycos does not pass the image width requirement
        // - excite has been sent already
        sumU43.feed.size === 2
        shoebox.sentMail.size === 2

        val (mail42, mail43) = {
          val (xs, ys) = shoebox.sentMail.partition(_.senderUserId.get == Id[User](42))
          (xs.head, ys.head)
        }

        mail42.senderUserId.get must beEqualTo(Id[User](42))
        val mail42body = mail42.htmlBody.toString
        // checking the domain-to-name mapper
        mail42body must contain(">www.kifi.com<")
        mail42body must contain(">Google<")

        // check that urls are in the emails
        mail42body must contain("https://www.kifi.com")
        mail42body must contain("https://www.google.com")

        // others-who-kept messages
        mail42body must contain("2 friends and 1 other kept this")
        mail42body must contain("1 friend and 2 others kept this")

        // read times
        mail42body must contain("45 min")
        mail42body must contain("15 min")

        // TopicAttribution
        mail42body must contain("Recommended because it’s trending in a topic you’re interested in: Searching")
        mail42body must contain("Recommended because it’s trending in a topic you’re interested in: Reading")

        // Friend Recommendations
        friends.foreach { user =>
          mail42body must contain("?friend=" + user.externalId)
        }

        mail43.senderUserId.get must beEqualTo(Id[User](43))
        val mail43body = mail43.htmlBody.toString
        mail43body must contain("42go.com")
        mail43body must contain("yahoo.com")
        mail43body must not contain "lycos.com"
        mail43body must not contain "excite.com"
        mail43body must contain("5 others kept this")

        val notSentIds = Set(5L)
        savedRecoModels.forall { models =>
          val (uri, reco, uriSumm) = models
          db.readOnlyMaster { implicit s =>
            if (notSentIds.contains(uri.id.get.id)) uriRecoRepo.get(reco.id.get).lastPushedAt must beNone
            else uriRecoRepo.get(reco.id.get).lastPushedAt must beSome
          }
        }
      }
    }

  }

}

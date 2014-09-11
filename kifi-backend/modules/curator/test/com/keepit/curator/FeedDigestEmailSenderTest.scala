package com.keepit.curator

import com.keepit.abook.{ FakeABookServiceClientImpl, ABookServiceClient, FakeABookServiceClientModule }
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.model.{ SocialUserInfo, User }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.{ SocialNetworks, SocialId }
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
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "FeedDigestEmailSender" should {

    "sends not-already-pushed keeps to users" in {
      withDb(modules: _*) { implicit injector =>
        val shoebox = shoeboxClientInstance()
        val sender = inject[FeedDigestEmailSender]
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

        val savedRecoModels = db.readWrite { implicit rw =>
          Seq(
            {
              val tuple = makeCompleteUriRecommendation(1, 42, 8f, "https://www.kifi.com", 10000)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1, friend2).map(_.id.get), others = 1)),
                topic = Some(TopicAttribution("Reading"))
              )))
            },
            {
              val tuple = makeCompleteUriRecommendation(2, 42, 9f, "https://www.google.com", 2500)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1.id.get), others = 2)),
                topic = Some(TopicAttribution("Searching"))
              )))
            },
            makeCompleteUriRecommendation(3, 43, 11f, "http://www.42go.com"),
            makeCompleteUriRecommendation(4, 43, 8.5f, "http://www.yahoo.com"),
            // this isn't in the recommendation list because image width is too small
            makeCompleteUriRecommendation(5, 43, 9f, "http://www.lycos.com", 250, Some(200)),
            // this isn't in recommendation list b/c it has already been sent
            {
              val tuple = makeCompleteUriRecommendation(6, 42, 9f, "http://www.excite.com")
              tuple.copy(_2 = tuple._2.withLastPushedAt(currentDateTime))
            },
            // shouldn't be in reco list b/c it's below threshold (8)
            makeCompleteUriRecommendation(7, 43, 7.99f, "https://www.bing.com"),
            // shouldn't be in reco list b/c image is too tall
            makeCompleteUriRecommendation(uriId = 8, userId = 43, masterScore = 9, url = "https://www.youtube.com/watch?v=BROWqjuTM0g", summaryImageHeight = Some(1001)),
            // shouldn't be in reco list b/c trashed
            {
              val tup = makeCompleteUriRecommendation(9, 42, 10f, "http://www.myspace.com")
              tup.copy(_2 = tup._2.copy(trashed = true))
            },
            // shouldn't be in reco list b/c kept
            {
              val tup = makeCompleteUriRecommendation(10, 42, 10f, "http://www.apple.com")
              tup.copy(_2 = tup._2.copy(kept = true))
            }
          ).map(tuple => saveUriModels(tuple, shoebox))
        }

        val sendFuture: Future[Seq[DigestRecoMail]] = sender.send()
        val summaries = Await.result(sendFuture, Duration(5, "seconds"))

        summaries.size === 8
        val sumU42 = summaries.find(_.userId.id == 42).get
        val sumU43 = summaries.find(_.userId.id == 43).get

        sumU42.feed.size === 2

        // lycos and excite should not be included because:
        // - lycos does not pass the image width requirement
        // - excite has been sent already
        sumU43.feed.size === 2

        // 2 sent to users
        // 2 copied to QA
        shoebox.sentMail.size === 4

        val (mail42, mail43) = {
          val (xs, ys) = shoebox.sentMail.filter(_.senderUserId.isDefined).partition(_.senderUserId.get == Id[User](42))
          (xs.head, ys.head)
        }

        mail42.senderUserId.get must beEqualTo(Id[User](42))
        val mail42body = mail42.htmlBody.toString
        // checking the domain-to-name mapper
        mail42body must contain(">www.kifi.com<")
        mail42body must contain(">Google<")

        // check that uri's for the recos are in the emails
        mail42body must contain("/r/e/1/recos/keep?id=" + savedRecoModels(0)._1.externalId)
        mail42body must contain("/r/e/1/recos/view?id=" + savedRecoModels(0)._1.externalId)
        mail42body must contain("/r/e/1/recos/send?id=" + savedRecoModels(1)._1.externalId)

        // others-who-kept messages
        mail42body must contain("2 friends and 1 other kept this")
        mail42body must contain("1 friend and 2 others kept this")

        // read times
        mail42body must contain("45 min")
        mail42body must contain("15 min")

        // TopicAttribution
        mail42body must contain("Recommended because it’s trending in a topic you’re interested in: Searching")
        mail42body must contain("Recommended because it’s trending in a topic you’re interested in: Reading")

        mail43.senderUserId.get must beEqualTo(Id[User](43))
        val mail43body = mail43.htmlBody.toString

        mail43body must not contain "lycos.com"
        mail43body must not contain "excite.com"
        mail43body must not contain savedRecoModels(4)._1.externalId.toString
        mail43body must not contain savedRecoModels(5)._1.externalId.toString

        mail43body must contain("5 others kept this")

        // check that uri's for the recos are in the emails
        mail43body must contain("/r/e/1/recos/keep?id=" + savedRecoModels(2)._1.externalId)
        mail43body must contain("/r/e/1/recos/send?id=" + savedRecoModels(3)._1.externalId)

        // conditionally show the "Connect Facebook" link if they haven't connected facebook
        mail42body must contain("Connect Facebook")
        mail43body must not contain "Connect Facebook"

        val sentRecoIds = Set(1L, 2L, 3L, 4L) // reco Ids that were just sent
        savedRecoModels.forall { models =>
          val (uri, reco, uriSumm) = models
          db.readOnlyMaster { implicit s =>
            val freshReco = uriRecoRepo.get(reco.id.get)
            if (sentRecoIds.contains(uri.id.get.id)) {
              freshReco.lastPushedAt must beSome
              freshReco.delivered === 1
            } else {
              freshReco.lastPushedAt must (if (reco.id.get.id == 6L) beSome else beNone)
              freshReco.delivered === 0
            }
          }
        }
      }
    }

  }

}

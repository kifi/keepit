package com.keepit.curator

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
import com.keepit.curator.model.{ UserAttribution, SeedAttribution, UriRecommendationRepo }
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
    FakeCacheModule())

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

        shoebox.saveUsers(friend1, friend2)

        val savedRecoModels = db.readWrite { implicit rw =>
          Seq(
            {
              val tuple = makeCompleteUriRecommendation(1, 42, 0.15f, "http://www.kifi.com", 10000)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1, friend2).map(_.id.get), others = 1))
              )))
            },
            {
              val tuple = makeCompleteUriRecommendation(2, 42, 0.99f, "http://www.google.com", 2500)
              tuple.copy(_2 = tuple._2.copy(attribution = SeedAttribution(
                user = Some(UserAttribution(friends = Seq(friend1.id.get), others = 2))
              )))
            },
            makeCompleteUriRecommendation(3, 43, 0.3f, "http://www.42go.com"),
            makeCompleteUriRecommendation(4, 43, 0.4f, "http://www.yahoo.com"),
            makeCompleteUriRecommendation(5, 43, 0.5f, "http://www.lycos.com"),
            {
              val tuple = makeCompleteUriRecommendation(6, 42, 0.99f, "http://www.excite.com")
              tuple.copy(_2 = tuple._2.withLastPushedAt(currentDateTime))
            }
          ).map(tuple => saveUriModels(tuple, shoebox))
        }

        val sendFuture: Future[Seq[DigestRecoMail]] = sender.send()
        val summaries = Await.result(sendFuture, Duration(5, "seconds"))

        summaries.size === 4
        val sumU42 = summaries.find(_.userId.id == 42).get
        val sumU43 = summaries.find(_.userId.id == 43).get

        sumU42.feed.size === 2
        sumU43.feed.size === 3
        shoebox.sentMail.size === 2
        val (mail42, mail43) = {
          val (xs, ys) = shoebox.sentMail.partition(_.senderUserId.get == Id[User](42))
          (xs.head, ys.head)
        }

        mail42.senderUserId.get must beEqualTo(Id[User](42))
        val mail42body = mail42.htmlBody.toString
        mail42body must contain("www.kifi.com")
        mail42body must contain("www.google.com")
        mail42body must contain("2 friends and 1 other kept this")
        mail42body must contain("1 friend and 2 others kept this")
        mail42body must contain("45 min")
        mail42body must contain("15 min")

        mail43.senderUserId.get must beEqualTo(Id[User](43))
        val mail43body = mail43.htmlBody.toString
        mail43body must contain("www.42go.com")
        mail43body must contain("www.yahoo.com")
        mail43body must contain("www.lycos.com")
        mail43body must contain("5 others kept this")

        val email = shoebox.sentMail(0)
        email.htmlBody.toString must contain("Hello Some")

        savedRecoModels.forall { models =>
          val (uri, reco, uriSumm) = models
          db.readOnlyMaster { implicit s =>
            uriRecoRepo.get(reco.id.get).lastPushedAt must beSome
          }
        }
      }
    }

  }

}

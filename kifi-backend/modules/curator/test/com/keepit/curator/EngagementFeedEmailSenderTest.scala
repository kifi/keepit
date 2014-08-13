package com.keepit.curator

import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time.{ currentDateTime, DEFAULT_DATE_TIME_ZONE }
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import commanders.email.{ EngagementFeedEmailSender, EngagementFeedSummary }
import model.UriRecommendationRepo
import org.specs2.mutable.Specification
import com.keepit.heimdal.FakeHeimdalServiceClientModule

import concurrent.duration.Duration
import concurrent.{ Await, Future }

class EngagementFeedEmailSenderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  val modules = Seq(
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeCacheModule())

  "EngagementFeedEmailSender" should {

    "sends not-already-pushed keeps to users" in {
      withDb(modules: _*) { implicit injector =>
        val shoebox = shoeboxClientInstance()
        val sender = inject[EngagementFeedEmailSender]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)
        val uriRecoRepo = inject[UriRecommendationRepo]

        val savedRecoModels = db.readWrite { implicit rw =>
          Seq(
            makeCompleteUriRecommendation(1, 42, 0.15f, "http://www.kifi.com"),
            makeCompleteUriRecommendation(2, 42, 0.99f, "http://www.google.com"),
            makeCompleteUriRecommendation(3, 43, 0.3f, "http://www.42go.com"),
            makeCompleteUriRecommendation(4, 43, 0.4f, "http://www.yahoo.com"),
            makeCompleteUriRecommendation(5, 43, 0.5f, "http://www.lycos.com"),
            {
              val tuple = makeCompleteUriRecommendation(6, 42, 0.99f, "http://www.excite.com")
              tuple.copy(_2 = tuple._2.withLastPushedAt(currentDateTime))
            }
          ).map(tuple => saveUriModels(tuple, shoebox))
        }

        val sendFuture: Future[Seq[EngagementFeedSummary]] = sender.send()
        val summaries = Await.result(sendFuture, Duration(5, "seconds"))

        summaries.size === 2
        summaries(0).feed.size === 2
        summaries(1).feed.size === 3
        shoebox.sentMail.size === 2

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

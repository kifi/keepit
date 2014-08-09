package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.model.URISummary
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl }
import commanders.email.{ EngagementFeedSummary, EngagementFeedEmailSender }
import model.UriRecommendationRepo
import org.specs2.mutable.Specification

import concurrent.duration.Duration
import concurrent.{ Await, Future }

class EngagementFeedEmailSenderTest extends Specification with CuratorTestInjector {
  import TestHelpers._

  val modules = Seq(
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeShoeboxServiceModule(),
    FakeCortexServiceClientModule(),
    FakeCacheModule())

  def setup()(implicit injector: Injector) = {
    val shoebox = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]

    (shoebox)
  }

  "EngagementFeedEmailSender" should {

    "sends to users in experiment" in {
      withDb(modules: _*) { implicit injector =>
        val (shoebox) = setup()
        val sender = inject[EngagementFeedEmailSender]
        val user1 = makeUser(42, shoebox)
        val user2 = makeUser(43, shoebox)

        val uriRecoRepo = inject[UriRecommendationRepo]
        val recos = db.readWrite { implicit rw =>
          Seq(makeUriRecommendation(1, 42, 0.15f),
            makeUriRecommendation(2, 42, 0.99f),
            makeUriRecommendation(3, 43, 0.3f),
            makeUriRecommendation(4, 43, 0.4f),
            makeUriRecommendation(5, 43, 0.5f)
          ).map(reco => uriRecoRepo.save(reco))
        }

        shoebox.saveURIs(Seq(makeNormalizedUri(1, "http://www.kifi.com"),
          makeNormalizedUri(2, "http://www.google.com"),
          makeNormalizedUri(3, "http://www.42go.com"),
          makeNormalizedUri(4, "http://www.yahoo.com"),
          makeNormalizedUri(5, "http://www.lycos.com")
        ): _*)

        recos.foreach { uriReco =>
          shoebox.saveURISummary(uriReco.uriId, URISummary(
            title = Some("Test " + uriReco.uriId),
            description = Some("Test Description " + uriReco.uriId),
            imageUrl = Some("image.jpg")))
        }

        val sendFuture: Future[Seq[EngagementFeedSummary]] = sender.send()

        val summaries = Await.result(sendFuture, Duration(5, "seconds"))

        summaries.size === 2
        summaries(0).feed.size === 2
        summaries(1).feed.size === 3
        shoebox.sentMail.size === 2
      }
    }

  }

}

package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.PublicFeedGenerationCommander
import com.keepit.curator.model.{ PublicFeedRepo, PublicFeed }
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.NormalizedURI
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class PublicFeedGenerationCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def modules = Seq(
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHealthcheckModule())

  private def setup()(implicit injector: Injector) = {
    val shoebox = shoeboxClientInstance()
    val (user1, user2) = (makeUser(42, shoebox).id.get, makeUser(43, shoebox).id.get)

    (user1, user2, shoebox)
  }
  def setupFeeds(): Seq[PublicFeed] = {
    val rec1 = makePublicFeed(1, 3.0f)
    val rec2 = makePublicFeed(2, 0.99f)
    val rec3 = makePublicFeed(3, 0.5f)
    Seq(rec1, rec2, rec3)
  }

  "RecommendationGenerationCommanderTest" should {
    "get public feeds" in {
      withDb(modules: _*) {
        implicit injector =>
          val repo = inject[PublicFeedRepo]
          db.readWrite {
            implicit s =>
              val feeds = setupFeeds()
              repo.save(feeds(0))
              repo.save(feeds(1))
              repo.save(feeds(2))
          }

          val commander = inject[PublicFeedGenerationCommander]

          val result = commander.getPublicFeeds(2)
          val feeds = Await.result(result, Duration(10, "seconds"))
          feeds.size === 2
          feeds(0).uriId === Id[NormalizedURI](1)
          feeds(0).publicMasterScore === 3.0f
      }
    }
  }

}


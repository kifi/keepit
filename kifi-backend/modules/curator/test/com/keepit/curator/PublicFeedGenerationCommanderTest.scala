package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.{ SeedIngestionCommander, RecommendationRetrievalCommander, PublicFeedGenerationCommander }
import com.keepit.curator.model.{ PublicFeedRepo, PublicFeed }
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceClientImpl
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class PublicFeedGenerationCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def modules = Seq(
    FakeExecutionContextModule(),
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
  def setupFeeds()(implicit injector: Injector): Seq[PublicFeed] = {
    val shoebox = shoeboxClientInstance()
    makeKeeps(Id[User](1), 1, shoebox, 1)
    makeKeeps(Id[User](2), 2, shoebox, 1)
    makeKeeps(Id[User](3), 3, shoebox, 1)

    val rec1 = makePublicFeed(1, 3.0f)
    val rec2 = makePublicFeed(2, 0.99f)
    val rec3 = makePublicFeed(3, 0.5f)
    Seq(rec1, rec2, rec3)
  }

  "PublicFeedGenerationCommanderTest" should {
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

          val seedCommander = inject[SeedIngestionCommander]
          Await.result(seedCommander.ingestAllKeeps(), Duration(10, "seconds"))

          val commander = inject[PublicFeedGenerationCommander]
          val retrivalCommander = inject[RecommendationRetrievalCommander]

          val feeds = retrivalCommander.topPublicRecos(None)
          feeds.size === 3

          val feeds1 = retrivalCommander.topPublicRecos(Some(Id[User](1)))
          feeds1.size === 2
          val feeds2 = retrivalCommander.topPublicRecos(Some(Id[User](2)))
          feeds2.size === 1
          val feeds3 = retrivalCommander.topPublicRecos(Some(Id[User](3)))
          feeds3.size === 0
      }
    }
  }

}


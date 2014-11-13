package com.keepit.curator

import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.db.{ Id, SequenceNumber }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.{ UriWeightingHelper, UriScoringHelper }
import com.keepit.curator.model.{ ScoredSeedItem, Keepers, SeedItem }
import com.keepit.graph.{ FakeGraphServiceClientImpl, GraphServiceClient, FakeGraphServiceModule }
import com.keepit.model.{ User, NormalizedURI }
import org.specs2.mutable.Specification
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.heimdal.FakeHeimdalServiceClientModule

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UriScoringHelperTest extends Specification with CuratorTestInjector {
  val modules = Seq(
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeCortexServiceClientModule(),
    FakeCacheModule(),
    FakeHeimdalServiceClientModule())

  private def makeSeedItems(): Seq[SeedItem] = {
    val seedItem1 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](1), url = "url1", seq = SequenceNumber[SeedItem](1), priorScore = None, timesKept = 1000, lastSeen = currentDateTime, keepers = Keepers.TooMany, discoverable = true)
    val seedItem2 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](2), url = "url2", seq = SequenceNumber[SeedItem](2), priorScore = None, timesKept = 10, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](3))), discoverable = true)
    val seedItem3 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](3), url = "url3", seq = SequenceNumber[SeedItem](3), priorScore = None, timesKept = 93, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](2))), discoverable = true)
    val seedItem4 = SeedItem(userId = Id[User](42), uriId = Id[NormalizedURI](4), url = "url4", seq = SequenceNumber[SeedItem](4), priorScore = None, timesKept = 20, lastSeen = currentDateTime, keepers = Keepers.ReasonableNumber(Seq(Id[User](1), Id[User](2))), discoverable = true)
    seedItem1 :: seedItem2 :: seedItem3 :: seedItem4 :: Nil
  }

  "UriScoringHelper" should {

    "get raw social scores" in {
      withInjector(modules: _*) { implicit injector =>
        val graph = inject[GraphServiceClient].asInstanceOf[FakeGraphServiceClientImpl]
        graph.setUserAndScorePairs()

        val uriScoringHelper = inject[UriScoringHelper]
        val uriBoostingHelper = inject[UriWeightingHelper]
        val res = uriScoringHelper(uriBoostingHelper(makeSeedItems), Set.empty)

        val scores = Await.result(res, Duration(10, "seconds"))

        scores(0).uriScores.socialScore === 0.0f
        scores(1).uriScores.socialScore should be > 0.0f
        scores(2).uriScores.socialScore should be > 0.0f
        scores(3).uriScores.socialScore should be > 0.0f

      }
    }
  }
}

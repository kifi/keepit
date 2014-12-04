package com.keepit.curator

import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.models.lda.{ LDATopic, LDAUserURIInterestScores }
import com.keepit.cortex.{ CortexServiceClient, FakeCortexServiceClientImpl, FakeCortexServiceClientModule }
import com.keepit.curator.commanders.{ UriScoringHelper, UriWeightingHelper }
import com.keepit.graph.{ FakeGraphServiceClientImpl, FakeGraphServiceModule, GraphServiceClient }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.User
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class UriScoringHelperTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  val modules = Seq(
    FakeGraphServiceModule(),
    FakeHttpClientModule(),
    FakeCortexServiceClientModule(),
    FakeCacheModule(),
    FakeHeimdalServiceClientModule())

  "UriScoringHelper" should {

    "get raw social scores" in {
      withInjector(modules: _*) { implicit injector =>
        val graph = inject[GraphServiceClient].asInstanceOf[FakeGraphServiceClientImpl]
        val userId = Id[User](42)
        graph.setUserAndScorePairs(userId)

        // set expectation for cortex client request to test that the topics get passed through
        val cortex = inject[CortexServiceClient].asInstanceOf[FakeCortexServiceClientImpl]
        cortex.batchUserURIsInterestsExpectations(userId) = (0 until 4).map { i =>
          LDAUserURIInterestScores(None, None, None, topic1 = Some(LDATopic(i + 1)), topic2 = Some(LDATopic(i + 2)))
        }

        val uriScoringHelper = inject[UriScoringHelper]
        val uriBoostingHelper = inject[UriWeightingHelper]
        val res = uriScoringHelper(uriBoostingHelper(makeSeedItems(userId)), Set.empty)

        val scores = Await.result(res, Duration(10, "seconds"))

        scores(0).uriScores.socialScore === 0.0f
        scores(1).uriScores.socialScore should be > 0.0f
        scores(2).uriScores.socialScore should be > 0.0f
        scores(3).uriScores.socialScore should be > 0.0f

        scores(0).topic1 === Some(LDATopic(1))
        scores(0).topic2 === Some(LDATopic(2))
        scores(1).topic1 === Some(LDATopic(2))
        scores(1).topic2 === Some(LDATopic(3))
        scores(2).topic1 === Some(LDATopic(3))
        scores(2).topic2 === Some(LDATopic(4))
      }
    }
  }
}

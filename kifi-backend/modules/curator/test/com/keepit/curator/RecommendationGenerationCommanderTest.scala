package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.{ RecommendationGenerationCommander }
import com.keepit.common.healthcheck.FakeHealthcheckModule

import com.keepit.curator.model._
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ UriRecommendationScores, User, NormalizedURI }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class RecommendationGenerationCommanderTest extends Specification with CuratorTestInjector {

  def modules = Seq(
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHealthcheckModule())

  def setup(): Seq[UriRecommendation] = {
    val rec1 = UriRecommendation(uriId = Id[NormalizedURI](1), userId = Id[User](42), masterScore = 0.15f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        multiplier = 0.01f),
      seen = false, clicked = false, kept = false, attribution = SeedAttribution.EMPTY)

    val rec2 = UriRecommendation(uriId = Id[NormalizedURI](2), userId = Id[User](42), masterScore = 0.99f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        multiplier = 1.5f),
      seen = false, clicked = false, kept = false, attribution = SeedAttribution.EMPTY)

    val rec3 = UriRecommendation(uriId = Id[NormalizedURI](3), userId = Id[User](42), masterScore = 0.5f,
      allScores = UriScores(socialScore = 0.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        multiplier = 1.0f),
      seen = false, clicked = false, kept = false, attribution = SeedAttribution(topic = Some(TopicAttribution("fun"))))

    Seq(rec1, rec2, rec3)
  }

  "RecommendationGenerationCommanderTest" should {
    "get adhoc recommendations" in {
      withDb(modules: _*) { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
          repo.save(recs(1))
          repo.save(recs(2))
        }

        val commander = inject[RecommendationGenerationCommander]
        val result1 = commander.getAdHocRecommendations(Id[User](42), 2, UriRecommendationScores(socialScore = Some(0.5f)))
        val recs1 = Await.result(result1, Duration(10, "seconds"))
        println(recs1(0).toString)
        recs1(0).userId === Id[User](42)
        recs1(0).score === 0.75f
        recs1(1).score === 0.005f

        val result2 = commander.getAdHocRecommendations(Id[User](42), 1, UriRecommendationScores())
        val recs2 = Await.result(result2, Duration(10, "seconds"))
        recs2(0).score === 49.5f

      }
    }

    "pre-compute Recommendations" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[RecommendationGenerationCommander]
        commander.precomputeRecommendations()
        1 === 1
      }
    }
  }
}

package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.cortex.models.lda.LDATopic
import com.keepit.curator.commanders.{ SeedIngestionCommander, RecommendationGenerationCommander }
import com.keepit.common.healthcheck.FakeHealthcheckModule

import com.keepit.curator.model._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ User, NormalizedURI }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class RecommendationGenerationCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeHealthcheckModule())

  private def setup()(implicit injector: Injector) = {
    val shoebox = shoeboxClientInstance()
    val (user1, user2) = (makeUser(42, shoebox).id.get, makeUser(43, shoebox).id.get)

    (user1, user2, shoebox)
  }

  def setupRecs(): Seq[UriRecommendation] = {
    val rec1 = UriRecommendation(uriId = Id[NormalizedURI](1), userId = Id[User](42), masterScore = 0.15f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        curationScore = None,
        multiplier = Some(0.01f),
        libraryInducedScore = Some(0f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution.EMPTY,
      topic1 = None, topic2 = None)

    val rec2 = UriRecommendation(uriId = Id[NormalizedURI](2), userId = Id[User](42), masterScore = 0.99f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 8.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        curationScore = None,
        multiplier = Some(1.5f),
        libraryInducedScore = Some(0f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution.EMPTY,
      topic1 = Some(LDATopic(1)), topic2 = Some(LDATopic(2)))

    val rec3 = UriRecommendation(uriId = Id[NormalizedURI](3), userId = Id[User](42), masterScore = 0.5f,
      allScores = UriScores(socialScore = 0.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        curationScore = None,
        multiplier = Some(1.0f),
        libraryInducedScore = Some(0f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution(topic = Some(TopicAttribution("fun"))),
      topic1 = None, topic2 = None)

    Seq(rec1, rec2, rec3)
  }

  "pre-compute recommendations" in {
    withDb(modules: _*) { implicit injector =>
      val (user1, user2, shoebox) = setup()
      val user1Keeps = makeKeeps(user1, 5, shoebox)
      val user2Keeps = makeKeeps(user2, 5, shoebox)
      val seedCommander = inject[SeedIngestionCommander]
      val seedItemRepo = inject[RawSeedItemRepo]
      val commander = inject[RecommendationGenerationCommander]
      Await.result(seedCommander.ingestAllKeeps(), Duration(10, "seconds"))
      db.readWrite { implicit session => seedItemRepo.assignSequenceNumbers(1000) }
      commander.precomputeRecommendations()
      val futUnit = commander.precomputeRecommendations()
      Await.result(futUnit, Duration(10, "seconds"))
      val result = commander.getTopRecommendations(Id[User](42), 1)
      val recs = Await.result(result, Duration(10, "seconds"))
      recs.size === 0
    }
  }
}

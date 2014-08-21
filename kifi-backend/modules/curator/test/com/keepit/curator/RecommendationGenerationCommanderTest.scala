package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.db.{ SequenceNumber, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.{ SeedIngestionCommander, RecommendationGenerationCommander }
import com.keepit.common.healthcheck.FakeHealthcheckModule

import com.keepit.curator.model._
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ Keep, Name, SystemValueRepo, UriRecommendationScores, User, NormalizedURI }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

import scala.concurrent.{ Future, Await }
import scala.concurrent.duration.Duration

class RecommendationGenerationCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

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
        multiplier = Some(0.01f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution.EMPTY)

    val rec2 = UriRecommendation(uriId = Id[NormalizedURI](2), userId = Id[User](42), masterScore = 0.99f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        multiplier = Some(1.5f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution.EMPTY)

    val rec3 = UriRecommendation(uriId = Id[NormalizedURI](3), userId = Id[User](42), masterScore = 0.5f,
      allScores = UriScores(socialScore = 0.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f,
        multiplier = Some(1.0f)),
      delivered = 0, clicked = 0, kept = false, attribution = SeedAttribution(topic = Some(TopicAttribution("fun"))))

    Seq(rec1, rec2, rec3)
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

          val commander = inject[RecommendationGenerationCommander]

          val result = commander.getPublicFeeds(2)
          val feeds = Await.result(result, Duration(10, "seconds"))
          feeds.size === 2
          feeds(0).uriId === Id[NormalizedURI](1)
          feeds(0).publicMasterScore === 3.0f
      }
    }

    "get adhoc recommendations" in {
      withDb(modules: _*) { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setupRecs()
          repo.save(recs(0))
          repo.save(recs(1))
          repo.save(recs(2))
        }

        val commander = inject[RecommendationGenerationCommander]
        val result1 = commander.getAdHocRecommendations(Id[User](42), 2, UriRecommendationScores(socialScore = Some(0.5f)))
        val recs1 = Await.result(result1, Duration(10, "seconds"))
        recs1(0).userId === Id[User](42)
        recs1(0).score === 0.75f
        recs1(1).score === 0.005f

        val result2 = commander.getAdHocRecommendations(Id[User](42), 1, UriRecommendationScores())
        val recs2 = Await.result(result2, Duration(10, "seconds"))
        recs2(0).score === 49.5f

      }
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
        Thread.sleep(5000)
        val result = commander.getTopRecommendations(Id[User](42), 1)
        val recs = Await.result(result, Duration(10, "seconds"))
        recs.size === 0
      }
    }

    "pre-compute public feeds" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, shoebox) = setup()
        val user1Keeps = makeKeeps(user1, 5, shoebox)
        val user2Keeps = makeKeeps(user2, 5, shoebox)
        val seedCommander = inject[SeedIngestionCommander]
        val seedItemRepo = inject[RawSeedItemRepo]
        val commander = inject[RecommendationGenerationCommander]
        Await.result(seedCommander.ingestAllKeeps(), Duration(10, "seconds"))
        db.readWrite { implicit session => seedItemRepo.assignSequenceNumbers(1000) }
        commander.precomputePublicFeeds()
        Thread.sleep(5000)
        val result = commander.getPublicFeeds(5)
        val feeds = Await.result(result, Duration(10, "seconds"))
        feeds.size === 5
      }
    }
  }
}

package com.keepit.curator

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.RecommendationCleanupCommander
import com.keepit.curator.model.{ PublicUriScores, PublicFeed, PublicFeedRepo, UriRecommendationRepo, UriRecommendation }
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.search.FakeSearchServiceClientModule
import org.joda.time.Days
import org.specs2.mutable.Specification
import com.keepit.common.time._

class RecommendationCleanupCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHealthcheckModule())

  def setupRecos(): Seq[UriRecommendation] = {
    val rec1 = makeUriRecommendationWithUpdateTimestamp(1, 42, 0.15f, currentDateTime.minusDays(20))
    val rec2 = makeUriRecommendationWithUpdateTimestamp(2, 42, 0.99f, currentDateTime.minusDays(20))
    val rec3 = makeUriRecommendationWithUpdateTimestamp(3, 42, 0.5f, currentDateTime.minusDays(20))
    val rec4 = makeUriRecommendationWithUpdateTimestamp(4, 42, 0.75f, currentDateTime.minusDays(20))
    val rec5 = makeUriRecommendationWithUpdateTimestamp(5, 42, 0.65f, currentDateTime.minusDays(20))
    val rec6 = makeUriRecommendationWithUpdateTimestamp(6, 42, 0.35f, currentDateTime.minusDays(20))
    Seq(rec1, rec2, rec3, rec4, rec5, rec6)
  }

  def setupPublicFeeds(): Seq[PublicFeed] = {
    val feed1 = PublicFeed(uriId = Id[NormalizedURI](1), publicMasterScore = 1.0f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    val feed2 = PublicFeed(uriId = Id[NormalizedURI](2), publicMasterScore = 0.9f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    val feed3 = PublicFeed(uriId = Id[NormalizedURI](3), publicMasterScore = 0.8f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    val feed4 = PublicFeed(uriId = Id[NormalizedURI](4), publicMasterScore = 0.7f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    val feed5 = PublicFeed(uriId = Id[NormalizedURI](5), publicMasterScore = 0.6f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    val feed6 = PublicFeed(uriId = Id[NormalizedURI](6), publicMasterScore = 0.5f, publicAllScores = PublicUriScores(1.0f, 1.0f, 1.0f, 1.0f, Some(1.0f), Some(1.0f)))
    Seq(feed1, feed2, feed3, feed4, feed5)
  }
  "RecommendationCleanupCommander" should {
    "delete old low master score items" in {
      withDb(modules: _*) { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          setupRecos().foreach(repo.save(_))
        }

        val commander = inject[RecommendationCleanupCommander]
        commander.cleanup(Some(4), Some(currentDateTime), false)

        db.readOnlyMaster { implicit s =>
          val recos = repo.getByTopMasterScore(Id[User](42), 6)
          recos.size === 4
          recos(0).masterScore === 0.99f
          recos(1).masterScore === 0.75f
          recos(2).masterScore === 0.65f
          recos(3).masterScore === 0.5f
        }
      }
    }
    "delete old items" in {
      withDb(modules: _*) { implicit injector =>
        val clock = inject[Clock].asInstanceOf[FakeClock]
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setupRecos()

          clock -= Days.days(30)
          recs.take(3).foreach(repo.save(_))

          clock += Days.days(30)
          recs.drop(3).foreach(repo.save(_))
        }

        val commander = inject[RecommendationCleanupCommander]
        commander.cleanup(Some(4), Some(currentDateTime), false)

        db.readOnlyMaster { implicit s =>
          val recos = repo.getByTopMasterScore(Id[User](42), 6)
          recos.size === 3
          recos(0).masterScore === 0.75f
          recos(1).masterScore === 0.65f
          recos(2).masterScore === 0.35f
        }
      }
    }
  }
}

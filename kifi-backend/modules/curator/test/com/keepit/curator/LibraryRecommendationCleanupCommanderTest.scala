package com.keepit.curator

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.RecommendationCleanupCommander
import com.keepit.curator.model.{ LibraryRecommendationRepo, PublicFeed, PublicFeedRepo, PublicUriScores, UriRecommendation, UriRecommendationRepo }
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

class LibraryRecommendationCleanupCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  def modules = Seq(
    FakeExecutionContextModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHealthcheckModule())

  def setupRecos() = {
    val rec1 = makeLibraryRecommendation(1, 42, 5)
    val rec2 = makeLibraryRecommendation(2, 42, 6)
    val rec3 = makeLibraryRecommendation(3, 42, 7)
    val rec4 = makeLibraryRecommendation(4, 42, 1.3f)
    val rec5 = makeLibraryRecommendation(5, 42, 1.2f)
    val rec6 = makeLibraryRecommendation(6, 42, 1.1f)
    Seq(rec1, rec2, rec3, rec4, rec5, rec6)
  }

  "LibraryRecommendationCleanupCommander" should {
    "delete old low master score items" in {
      withDb(modules: _*) { implicit injector =>
        val repo = inject[LibraryRecommendationRepo]
        val allRecos = db.readWrite { implicit s =>
          setupRecos().map { r => repo.save(r) }
        }

        val commander = inject[LibraryRecommendationCleanupCommander]
        commander.cleanupLowMasterScoreRecos(Some(3), Some(currentDateTime))

        db.readOnlyMaster { implicit s =>
          val recos = repo.getByTopMasterScore(Id[User](42), 6)
          recos.size === 3
          recos(0).masterScore === 7
          recos(1).masterScore === 6
          recos(2).masterScore === 5
        }
      }
    }
  }
}

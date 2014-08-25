package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.{ RecommendationCleanupCommander, RecommendationGenerationCommander }
import com.keepit.curator.model.{ UriRecommendationRepo, UriRecommendation }
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.User
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

class RecommendationCleanupCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {
  def modules = Seq(
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeHealthcheckModule())

  def setup(): Seq[UriRecommendation] = {
    val rec1 = makeUriRecommendation(1, 42, 0.15f)
    val rec2 = makeUriRecommendation(2, 42, 0.99f)
    val rec3 = makeUriRecommendation(3, 42, 0.5f)
    val rec4 = makeUriRecommendation(4, 42, 0.75f)
    val rec5 = makeUriRecommendation(5, 42, 0.65f)
    val rec6 = makeUriRecommendation(6, 42, 0.35f)
    Seq(rec1, rec2, rec3, rec4, rec5, rec6)
  }

  "RecommendationCleanupCommander" should {
    "mark low master score recos to inactive" in {
      withDb(modules: _*) { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
          repo.save(recs(1))
          repo.save(recs(2))
          repo.save(recs(3))
          repo.save(recs(4))
          repo.save(recs(5))
        }

        val commander = inject[RecommendationCleanupCommander]
        val update = commander.cleanupLowMasterScoreRecos(Some(4))
        update === true

        db.readOnlyMaster { implicit s =>
          val recos = repo.getByTopMasterScore(Id[User](42), 5)
          recos.size === 4
          recos(0).masterScore === 0.99f
          recos(1).masterScore === 0.75f
          recos(2).masterScore === 0.65f
          recos(3).masterScore === 0.5f
        }
      }
    }
  }
}

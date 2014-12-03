package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.commanders.LibraryRecommendationGenerationCommander
import com.keepit.curator.model.{ CuratorLibraryInfoSequenceNumberAssigner, CuratorLibraryInfoRepo, LibraryRecommendationRepo }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ ExperimentType, UserExperiment }
import com.keepit.search.FakeSearchServiceClientModule
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryRecommendationGenerationCommanderTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

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

  "pre-compute library recommendations" in {
    withDb(modules: _*) { implicit injector =>
      val (user1Id, user2Id, shoebox) = setup()
      shoebox.saveUserExperiment(UserExperiment(userId = user1Id, experimentType = ExperimentType.CURATOR_LIBRARY_RECOS))
      shoebox.saveUserExperiment(UserExperiment(userId = user2Id, experimentType = ExperimentType.CURATOR_LIBRARY_RECOS))

      val libRecoGenCommander = inject[LibraryRecommendationGenerationCommander]
      val libRecoRepo = inject[LibraryRecommendationRepo]
      val libInfoRepo = inject[CuratorLibraryInfoRepo]

      val (lib1, lib2) = db.readWrite { implicit s =>
        val lib1 = saveLibraryInfo(100, 600)
        val lib2 = saveLibraryInfo(101, 601)
        (lib1, lib2)
      }
      inject[CuratorLibraryInfoSequenceNumberAssigner].assignSequenceNumbers()

      val preComputeF = libRecoGenCommander.precomputeRecommendations()
      Await.result(preComputeF, Duration(555, "seconds"))

      db.readOnlyMaster { implicit s =>
        val libRecosUser1 = libRecoRepo.getByUserId(user1Id)
        libRecosUser1.size === 2

        val libRecosUser2 = libRecoRepo.getByUserId(user1Id)
        libRecosUser2.size === 2
      }
    }
  }
}

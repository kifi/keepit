package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.{ FakeCortexServiceClientImpl, CortexServiceClient, FakeCortexServiceClientModule }
import com.keepit.curator.commanders.LibraryRecommendationGenerationCommander
import com.keepit.curator.model.{ CuratorLibraryInfoSequenceNumberAssigner, CuratorLibraryInfoRepo, LibraryRecommendationRepo }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.{ FakeGraphServiceClientImpl, GraphServiceClient, FakeGraphServiceModule }
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

      val (lib1, lib2, lib3) = db.readWrite { implicit s =>
        val lib1 = saveLibraryInfo(100, 600)
        val lib2 = saveLibraryInfo(101, 601)
        val lib3 = saveLibraryInfo(102, 602)
        (lib1, lib2, lib3)
      }
      inject[CuratorLibraryInfoSequenceNumberAssigner].assignSequenceNumbers()

      // interest scoring
      val cortex = inject[CortexServiceClient].asInstanceOf[FakeCortexServiceClientImpl]
      cortex.userLibraryScoreExpectations((user1Id, lib3.libraryId)) = Some(1f)
      cortex.userLibraryScoreExpectations((user2Id, lib3.libraryId)) = Some(0.3f)

      // social scoring
      val graph = inject[GraphServiceClient].asInstanceOf[FakeGraphServiceClientImpl]
      val userScores = graph.setUserAndScorePairs(user1Id)
      db.readWrite { implicit rw =>
        saveLibraryMembership(userScores(0).userId, lib1.libraryId)
        saveLibraryMembership(userScores(1).userId, lib1.libraryId, true)
      }

      val preComputeF = libRecoGenCommander.precomputeRecommendations()
      Await.result(preComputeF, Duration(555, "seconds"))

      db.readOnlyMaster { implicit s =>
        val libRecosUser1 = libRecoRepo.getByUserId(user1Id).sortBy(_.masterScore)
        libRecosUser1.size === 3
        libRecosUser1(0).allScores.socialScore === 0
        libRecosUser1(1).allScores.socialScore > 0

        // highest social score is because a connected user is the owner of the recommended library
        libRecosUser1(2).allScores.socialScore > libRecosUser1(1).allScores.socialScore

        val libRecosUser2 = libRecoRepo.getByUserId(user2Id).sortBy(_.masterScore)
        libRecosUser2.size === 3

        // test that lower interest score has the lowest master score (everything else is the same)
        libRecosUser2(0).libraryId === lib3.libraryId
        libRecosUser2(0).allScores.interestScore < libRecosUser2(1).allScores.interestScore
      }
    }
  }
}

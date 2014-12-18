package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.{ CortexServiceClient, FakeCortexServiceClientImpl, FakeCortexServiceClientModule }
import com.keepit.curator.commanders.LibraryRecommendationGenerationCommander
import com.keepit.curator.model.{ CuratorLibraryMembershipInfoStates, CuratorLibraryMembershipInfo, CuratorLibraryMembershipInfoRepo, LibraryRecommendationGenerationStateRepo, LibraryRecommendationGenerationState, CuratorLibraryInfoRepo, CuratorLibraryInfoSequenceNumberAssigner, LibraryRecommendationRepo }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.{ FakeGraphServiceClientImpl, FakeGraphServiceModule, GraphServiceClient }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ LibraryAccess, ExperimentType, UserExperiment }
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

      // more users to sanity check reactive lock
      val moreUsers = (44 to 54) map { i => makeUser(i, shoebox).id.get }
      val allUsers = Seq(user1Id, user2Id) ++ moreUsers

      val libRecoGenCommander = inject[LibraryRecommendationGenerationCommander]
      val libRecoRepo = inject[LibraryRecommendationRepo]
      val libInfoRepo = inject[CuratorLibraryInfoRepo]

      val (lib1, lib2, lib3, lib4, lib5) = db.readWrite { implicit s =>
        val lib1 = saveLibraryInfo(100, 600)
        val lib2 = saveLibraryInfo(101, 601)
        val lib3 = saveLibraryInfo(102, 602)
        val lib4 = saveLibraryInfo(103, 603, keepCount = 2)
        val lib5 = saveLibraryInfo(104, 604)

        inject[CuratorLibraryMembershipInfoRepo].save(CuratorLibraryMembershipInfo(access = LibraryAccess.READ_ONLY,
          userId = user1Id, libraryId = lib5.libraryId, state = CuratorLibraryMembershipInfoStates.ACTIVE))

        (lib1, lib2, lib3, lib4, lib5)
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
        saveLibraryMembership(userScores(1).userId, lib1.libraryId, owner = true)
      }

      val preComputeF = libRecoGenCommander.precomputeRecommendations()
      Await.result(preComputeF, Duration(5, "seconds"))

      libRecoGenCommander.recommendationGenerationLock.waiting === 0

      db.readOnlyMaster { implicit s =>
        val stateRepo = inject[LibraryRecommendationGenerationStateRepo]

        val maxLibSeq = libInfoRepo.getByLibraryId(lib5.libraryId).get.seq
        allUsers.foreach { userId => stateRepo.getByUserId(userId).get.seq === maxLibSeq }

        val libRecosUser1 = libRecoRepo.getByUserId(user1Id).sortBy(_.masterScore)
        libRecosUser1.size === 3

        libRecosUser1.exists(_.libraryId == lib4.libraryId) must beFalse // keepCount too low
        libRecosUser1(0).allScores.socialScore === 0
        libRecosUser1(1).allScores.socialScore > 0

        // highest social score is because a connected user is the owner of the recommended library
        libRecosUser1(2).allScores.socialScore > libRecosUser1(1).allScores.socialScore

        val libRecosUser2 = libRecoRepo.getByUserId(user2Id).sortBy(_.masterScore)
        libRecosUser2.size === 4

        // test that lower interest score has the lowest master score (everything else is the same)
        libRecosUser2(0).libraryId === lib3.libraryId
        libRecosUser2(0).allScores.interestScore < libRecosUser2(1).allScores.interestScore
      }
    }
  }
}

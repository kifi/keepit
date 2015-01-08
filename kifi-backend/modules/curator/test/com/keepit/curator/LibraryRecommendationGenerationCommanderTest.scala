package com.keepit.curator

import com.google.inject.Injector
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.cortex.{ CortexServiceClient, FakeCortexServiceClientImpl, FakeCortexServiceClientModule }
import com.keepit.curator.commanders.LibraryRecommendationGenerationCommander
import com.keepit.curator.model._
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

      val (lib0, lib1, lib2, lib3, lib4) = db.readWrite { implicit s =>
        val lib0 = saveLibraryInfo(105, 604, name = Some("Bookmarks"))
        val lib1 = saveLibraryInfo(100, 600)
        val lib2 = saveLibraryInfo(101, 601)
        val lib3 = saveLibraryInfo(102, 602)
        val lib4 = saveLibraryInfo(103, 603, keepCount = 2)

        (lib0, lib1, lib2, lib3, lib4)
      }
      inject[CuratorLibraryInfoSequenceNumberAssigner].assignSequenceNumbers()
      val lib5 = db.readWrite { implicit s => saveLibraryInfo(104, 604) }
      inject[CuratorLibraryInfoSequenceNumberAssigner].assignSequenceNumbers()

      // these are existing recommendations with a bad name
      val (badReco1, badReco2) = db.readWrite { implicit s =>
        (
          libRecoRepo.save(makeLibraryRecommendation(lib0.libraryId.id.toInt, user1Id.id.toInt, 5).copy(id = None)),
          libRecoRepo.save(makeLibraryRecommendation(lib0.libraryId.id.toInt, user2Id.id.toInt, 5).copy(id = None))
        )
      }

      badReco1.state === LibraryRecommendationStates.ACTIVE
      badReco2.state === LibraryRecommendationStates.ACTIVE

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
        saveLibraryMembership(user1Id, lib5.libraryId)
      }

      val preComputeF = libRecoGenCommander.precomputeRecommendations()
      Await.result(preComputeF, Duration(5, "seconds"))

      libRecoGenCommander.recommendationGenerationLock.waiting === 0

      def isActive(reco: LibraryRecommendation): Boolean = reco.state == LibraryRecommendationStates.ACTIVE

      db.readOnlyMaster { implicit s =>
        val stateRepo = inject[LibraryRecommendationGenerationStateRepo]

        val maxLibSeq = libInfoRepo.getByLibraryId(lib5.libraryId).get.seq
        allUsers.foreach { userId => stateRepo.getByUserId(userId).get.seq === maxLibSeq }

        val (libRecosUser1, inactiveLibRecos1) = libRecoRepo.getByUserId(user1Id).sortBy(_.masterScore).partition(isActive)
        libRecosUser1.size === 3
        inactiveLibRecos1.size === 1

        libRecosUser1.exists(_.libraryId == lib4.libraryId) must beFalse // keepCount too low
        libRecosUser1(0).allScores.socialScore === 0
        libRecosUser1(1).allScores.socialScore > 0

        // highest social score is because a connected user is the owner of the recommended library
        libRecosUser1(2).allScores.socialScore > libRecosUser1(1).allScores.socialScore

        val (libRecosUser2, inactiveLibRecos2) = libRecoRepo.getByUserId(user2Id).sortBy(_.masterScore).partition(isActive)
        libRecosUser2.size === 4
        inactiveLibRecos2.size === 1

        // test that lower interest score has the lowest master score (everything else is the same)
        libRecosUser2(0).libraryId === lib3.libraryId
        libRecosUser2(0).allScores.interestScore < libRecosUser2(1).allScores.interestScore

        // ensure that this "badly named" reco were set to inactive
        inactiveLibRecos1.find(_.id == badReco1.id).get.state === LibraryRecommendationStates.INACTIVE
        inactiveLibRecos2.find(_.id == badReco2.id).get.state === LibraryRecommendationStates.INACTIVE
      }
    }
  }
}

package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.model.{ LibraryRecommendation, LibraryRecommendationRepo }
import com.keepit.model.{ LibraryRecommendationFeedback, User }
import org.specs2.mutable.Specification

class LibraryRecommendationRepoTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def setup(): Seq[LibraryRecommendation] = {
    val rec1 = makeLibraryRecommendation(1, 42, 0.15f)
    val rec2 = makeLibraryRecommendation(2, 42, 0.99f)
    val rec3 = makeLibraryRecommendation(3, 42, 0.5f)
    Seq(rec1, rec2, rec3)
  }

  "LibraryRecommendationRepo" should {

    "get top master score of recommendations" in {
      withDb() { implicit injector =>
        val repo = inject[LibraryRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
          repo.save(recs(1))
          repo.save(recs(2))
        }

        db.readOnlyMaster { implicit s =>
          val recs = repo.getByTopMasterScore(Id[User](42), 2)
          recs(0).masterScore === 0.99f
          recs(1).masterScore === 0.5f
        }
      }
    }

    "updates followed, clicked, trashed" in withDb() { implicit injector =>
      val repo = inject[LibraryRecommendationRepo]
      val reco1 = db.readWrite { implicit s =>
        val recs = setup()
        repo.save(recs(0))
      }

      reco1.followed === false
      reco1.clicked === 0
      reco1.trashed === false

      db.readWrite { implicit rw =>
        repo.updateLibraryRecommendationFeedback(reco1.userId, reco1.libraryId, LibraryRecommendationFeedback(
          followed = Some(true), trashed = Some(true), clicked = Some(true)))
      }

      db.readOnlyMaster { implicit s =>
        val reco = repo.getByLibraryAndUserId(reco1.libraryId, reco1.userId).get
        reco.followed === true
        reco.clicked === 1
        reco.trashed === true
      }
    }
  }
}

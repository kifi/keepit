package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.model.{ UriScores, UriRecommendation, UriRecommendationRepo }
import com.keepit.model.{ UriRecommendationFeedback, User, NormalizedURI }
import org.specs2.mutable.Specification

class UriRecommendationRepoTest extends Specification with CuratorTestInjector with CuratorTestHelpers {

  def setup(): Seq[UriRecommendation] = {
    val rec1 = makeUriRecommendation(1, 42, 0.15f)
    val rec2 = makeUriRecommendation(2, 42, 0.99f)
    val rec3 = makeUriRecommendation(3, 42, 0.5f)
    Seq(rec1, rec2, rec3)
  }

  "UriRecommendationRepo" should {
    "update uri recommendation feedback" in {
      withDb() { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
          val feedback = UriRecommendationFeedback(delivered = Some(1), clicked = Some(1))
          val update = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback)

          update should beTrue
        }

        db.readOnlyMaster { implicit s =>
          val rec1 = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1.delivered === 1
          rec1.clicked === 1
          rec1.kept === false
          rec1.trashed === false
          rec1.markedBad === false
        }
      }
    }

    "get top master score of recommendations" in {
      withDb() { implicit injector =>
        val repo = inject[UriRecommendationRepo]
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
  }
}

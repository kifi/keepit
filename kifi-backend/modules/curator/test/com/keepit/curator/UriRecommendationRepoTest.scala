package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.model.{ UriScores, UriRecommendation, UriRecommendationRepo }
import com.keepit.model.{ UriRecommendationFeedback, User, NormalizedURI }
import org.specs2.mutable.Specification

class UriRecommendationRepoTest extends Specification with CuratorTestInjector {

  def setup(): Seq[UriRecommendation] = {
    val rec1 = UriRecommendation(uriId = Id[NormalizedURI](1), userId = Id[User](42), masterScore = 0.15f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f),
      seen = false, clicked = false, kept = false)

    val rec2 = UriRecommendation(uriId = Id[NormalizedURI](2), userId = Id[User](42), masterScore = 0.99f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f),
      seen = false, clicked = false, kept = false)

    val rec3 = UriRecommendation(uriId = Id[NormalizedURI](3), userId = Id[User](42), masterScore = 0.5f,
      allScores = UriScores(socialScore = 1.0f,
        popularityScore = 1.0f,
        overallInterestScore = 1.0f,
        recentInterestScore = 1.0f,
        recencyScore = 1.0f,
        priorScore = 1.0f,
        rekeepScore = 1.0f,
        discoveryScore = 1.0f),
      seen = false, clicked = false, kept = false)

    Seq(rec1, rec2, rec3)
  }

  "UriRecommendationRepo" should {
    "update uri recommendation feedback" in {
      withDb() { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
          val feedback = Map(UriRecommendationFeedback.seen -> true, UriRecommendationFeedback.clicked -> false)
          val update = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback)

          update should beTrue
        }

        db.readOnlyMaster { implicit s =>
          val rec1 = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1.seen === true
          rec1.clicked === false
          rec1.kept === false
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
          val recs = repo.getByTopMasterScore(Id[User](42), 2, None)
          recs(0).masterScore === 0.99f
          recs(1).masterScore === 0.5f
        }
      }
    }
  }
}

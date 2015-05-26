package com.keepit.curator

import com.keepit.common.db.Id
import com.keepit.curator.model.{ UriRecommendation, UriRecommendationRepo }
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
          val feedback1 = UriRecommendationFeedback(clicked = Some(true))
          val update1 = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback1)

          update1 should beTrue

          val rec1 = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1.viewed === 0
          rec1.clicked === 1
          rec1.kept === false
          rec1.trashed === false

          val feedback2 = UriRecommendationFeedback(clicked = None, trashed = Some(true))
          val update2 = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback2)

          update2 should beTrue

          val rec1Update = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1Update.viewed === 0
          rec1Update.clicked === 1
          rec1Update.kept === false
          rec1Update.trashed === true

          val feedback3 = UriRecommendationFeedback(clicked = Some(false), kept = Some(true), trashed = Some(true))
          val update3 = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback3)

          update3 should beTrue

          val rec1Update2 = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1Update2.viewed === 0
          rec1Update2.clicked === 1
          rec1Update2.kept === true
          rec1Update2.trashed === true

          val feedback4 = UriRecommendationFeedback(clicked = None, trashed = Some(false))
          val update4 = repo.updateUriRecommendationFeedback(Id[User](42), Id[NormalizedURI](1), feedback4)

          update4 should beTrue

          val rec1Update3 = repo.getByUriAndUserId(Id[NormalizedURI](1), Id[User](42), None).get

          rec1Update3.viewed === 0
          rec1Update3.clicked === 1
          rec1Update3.kept === true
          rec1Update3.trashed === false

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

    "increment delivered count of recommendation" in {
      withDb() { implicit injector =>
        val repo = inject[UriRecommendationRepo]
        val reco = db.readWrite { implicit s =>
          val recs = setup()
          repo.save(recs(0))
        }
        db.readWrite { implicit session =>
          repo.incrementDeliveredCount(reco.id.get)
        }
        db.readOnlyReplica { implicit session => repo.get(reco.id.get) }.viewed === 1
      }

    }

  }
}

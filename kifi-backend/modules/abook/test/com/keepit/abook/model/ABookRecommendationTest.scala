package com.keepit.abook.model

import org.specs2.mutable.Specification
import com.keepit.abook.ABookTestInjector
import com.keepit.common.db.Id

class ABookRecommendationTest extends Specification with ABookTestInjector {

  "FriendRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val friendRecoRepo = inject[FriendRecommendationRepo]
        db.readWrite { implicit session =>
          friendRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          friendRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          friendRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          friendRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          friendRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          friendRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }

  "FacebookInviteRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val facebookInviteRecoRepo = inject[FacebookInviteRecommendationRepo]
        db.readWrite { implicit session =>
          facebookInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          facebookInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          facebookInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          facebookInviteRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          facebookInviteRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          facebookInviteRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }

  "LinkedInInviteRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val linkedInInviteRecoRepo = inject[LinkedInInviteRecommendationRepo]
        db.readWrite { implicit session =>
          linkedInInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          linkedInInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          linkedInInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          linkedInInviteRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          linkedInInviteRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          linkedInInviteRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }
  "EmailInviteRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val emailInviteRecoRepo = inject[EmailInviteRecommendationRepo]
        db.readWrite { implicit session =>
          emailInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          emailInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          emailInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          emailInviteRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          emailInviteRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          emailInviteRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }

  "TwitterRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val twitterInviteRecoRepo = inject[TwitterInviteRecommendationRepo]
        db.readWrite { implicit session =>
          twitterInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          twitterInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          twitterInviteRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          twitterInviteRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          twitterInviteRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          twitterInviteRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }
}

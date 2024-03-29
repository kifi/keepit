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

  "OrganizationMemberRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val memberRecoRepo = inject[OrganizationMemberRecommendationRepo]
        db.readWrite { implicit session =>
          memberRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42), Left(Id(420)))
          memberRecoRepo.recordIrrelevantRecommendation(Id(134), Id(42), Right(Id(420)))
          memberRecoRepo.recordIrrelevantRecommendation(Id(134), Id(420), Left(Id(430)))
          memberRecoRepo.recordIrrelevantRecommendation(Id(42), Id(420), Right(Id(430)))
        }
        db.readOnlyMaster { implicit session =>
          memberRecoRepo.getIrrelevantRecommendations(Id(134)) === Set(Left(Id(420)), Right(Id(420)), Left(Id(430)))
          memberRecoRepo.getIrrelevantRecommendations(Id(42)) === Set(Right(Id(430)))
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
  "UserEmailInviteRecommendationRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val emailInviteRecoRepo = inject[UserEmailInviteRecommendationRepo]
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

  "OrganizationRecommendationForUserRepo" should {
    "track irrelevant recommendations" in {
      withDb() { implicit injector =>
        val organizationRecoForUserRepo = inject[OrganizationRecommendationForUserRepo]
        db.readWrite { implicit session =>
          organizationRecoForUserRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          organizationRecoForUserRepo.recordIrrelevantRecommendation(Id(134), Id(42))
          organizationRecoForUserRepo.recordIrrelevantRecommendation(Id(134), Id(420))
          organizationRecoForUserRepo.recordIrrelevantRecommendation(Id(42), Id(420))
        }
        db.readOnlyMaster { implicit session =>
          println(organizationRecoForUserRepo.getIrrelevantRecommendations(Id(134)))
          println(organizationRecoForUserRepo.getIrrelevantRecommendations(Id(42)))
        }
        db.readOnlyMaster { implicit session =>
          organizationRecoForUserRepo.getIrrelevantRecommendations(Id(134)) === Set(Id(42), Id(420))
          organizationRecoForUserRepo.getIrrelevantRecommendations(Id(42)) === Set(Id(420))
        }
      }
    }
  }
}

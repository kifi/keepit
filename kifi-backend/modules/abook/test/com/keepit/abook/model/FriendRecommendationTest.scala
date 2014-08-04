package com.keepit.abook.model

import org.specs2.mutable.Specification
import com.keepit.abook.ABookTestInjector
import com.keepit.common.db.Id

class FriendRecommendationTest extends Specification with ABookTestInjector {

  "FriendRecommendationRepo" should withDb() { implicit injector =>
    "track irrelevant recommendations" in {
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

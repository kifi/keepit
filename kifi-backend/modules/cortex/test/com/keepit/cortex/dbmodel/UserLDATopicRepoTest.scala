package com.keepit.cortex.dbmodel

import org.specs2.mutable.Specification
import com.keepit.cortex.CortexTestInjector
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.models.lda._

class UserLDATopicRepoTest extends Specification with CortexTestInjector {
  "user lda topic repo" should {
    "work" in {
      withDb() { implicit injector =>
        val userTopicRepo = inject[UserLDAInterestsRepo]

        val topic = UserLDAInterests(userId = Id[User](1), version = ModelVersion[DenseLDA](1), userTopicMean = Some(UserTopicMean(Array(0.5f, 0.25f, 0.15f, 0.1f))))
        db.readWrite { implicit s =>
          userTopicRepo.save(topic)
          userTopicRepo.getByUser(Id[User](1), ModelVersion[DenseLDA](1)).get.userTopicMean.get.mean.toSeq === Seq(0.5f, 0.25f, 0.15f, 0.1f)
          userTopicRepo.getByUser(Id[User](1), ModelVersion[DenseLDA](2)) === None
          userTopicRepo.getByUser(Id[User](2), ModelVersion[DenseLDA](1)) === None
        }

      }
    }
  }
}

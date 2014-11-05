package com.keepit.cortex.models.lda

import com.keepit.common.db.Id
import com.keepit.cortex.core.ModelVersion
import com.keepit.cortex.dbmodel.{ UserTopicMean, UserLDAInterests, UserLDAInterestsRepo }
import com.keepit.model.User
import org.specs2.mutable.Specification
import com.keepit.cortex.CortexTestInjector
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess
import math.sqrt

class UserLDAStatisticsTest extends Specification with CortexTestInjector with LDADbTestHelper {
  "user lda stats updater" should {
    "correctly compute the stats and persist it in store and cache" in {
      withDb() { implicit injector =>

        val repo = inject[UserLDAInterestsRepo]

        db.readWrite { implicit s =>
          repo.save(UserLDAInterests(userId = Id[User](1), version = ModelVersion[DenseLDA](1), numOfEvidence = 200, userTopicMean = Some(UserTopicMean(Array(0.5f, 0.5f))),
            numOfRecentEvidence = 0, userRecentTopicMean = None, overallSnapshot = None, recencySnapshot = None, overallSnapshotAt = None, recencySnapshotAt = None))
          repo.save(UserLDAInterests(userId = Id[User](2), version = ModelVersion[DenseLDA](1), numOfEvidence = 200, userTopicMean = Some(UserTopicMean(Array(0f, 1f))),
            numOfRecentEvidence = 0, userRecentTopicMean = None, overallSnapshot = None, recencySnapshot = None, overallSnapshotAt = None, recencySnapshotAt = None))
        }

        val cache = inject[UserLDAStatisticsCache]
        val store = new InMemoryUserLDAStatisticsStore

        val updater = new UserLDAStatisticsUpdater(db, repo, uriReps, store, cache)
        updater.update()

        val stat = cache.get(UserLDAStatisticsCacheKey(uriRep.version)).get
        stat.min === Array(0f, 0.5f)
        stat.max === Array(0.5f, 1f)
        stat.mean === Array(0.25f, 0.75f)
        val std = 1f / sqrt(8f).toFloat
        stat.std === Array(std, std)

      }
    }
  }
}

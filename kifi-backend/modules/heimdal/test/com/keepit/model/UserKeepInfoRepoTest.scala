package com.keepit.model

import com.keepit.model.helprank.UserBookmarkClicksRepo
import org.specs2.mutable.Specification
import com.keepit.common.db.Id
import com.keepit.test._

class UserKeepInfoRepoTest extends Specification with HeimdalTestInjector {
  "userKeepInfoRepo" should {
    "keep counts" in {
      withDb() { implicit injector =>
        val N = 10
        val userIds = (1 to N).map { Id[User](_) }
        val uriIds = (1 to N).map { Id[NormalizedURI](_) }
        val repo = inject[UserBookmarkClicksRepo]

        (userIds zip uriIds) foreach {
          case (userId, uriId) =>
            db.readWrite { implicit s =>
              val numSelf = userId.id.toInt
              val numOther = N - numSelf
              (0 until numSelf).foreach { i => repo.increaseCounts(userId, uriId, isSelf = true) }
              (0 until numOther).foreach { i => repo.increaseCounts(userId, uriId, isSelf = false) }
            }
        }

        (userIds zip uriIds) foreach {
          case (userId, uriId) =>
            val record = db.readOnlyMaster { implicit s =>
              repo.getByUserUri(userId, uriId)
            }

            val numSelf = userId.id.toInt
            val numOther = N - numSelf
            record.get.selfClicks === numSelf
            record.get.otherClicks === numOther
        }
      }
    }
  }
}

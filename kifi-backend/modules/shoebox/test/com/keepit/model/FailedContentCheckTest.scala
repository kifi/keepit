package com.keepit.model

import com.keepit.common.time._
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.test._

class FailedContentCheckTest extends Specification with ShoeboxTestInjector {
  "FailedContentCheckRepo" should {
    "work" in {
      val u1 = "www.test.com/index"
      val u2 = "www.test.com"
      withDb() { implicit injector =>
        db.readWrite { implicit s =>
          failedContentCheckRepo.createOrIncrease(u1, u2)
          failedContentCheckRepo.createOrIncrease(u1, u2)
          failedContentCheckRepo.createOrIncrease(u1, u2)
          failedContentCheckRepo.createOrIncrease(u2, u1)
          failedContentCheckRepo.createOrIncrease(u2, u1)
        }

        db.readOnlyMaster { implicit s =>
          val r = failedContentCheckRepo.getByUrls(u1, u2)
          r.get.counts === 5
          r.get.state === FailedContentCheckStates.ACTIVE

          // reverse pair works
          failedContentCheckRepo.getByUrls(u2, u1)
          r.get.counts === 5
          r.get.state === FailedContentCheckStates.ACTIVE
          val ancient = new DateTime(2000, 1, 1, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
          failedContentCheckRepo.getRecentCountByURL(u1, ancient) === 1
        }
      }
    }
  }
}

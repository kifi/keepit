package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._

class FailedUriNormalizationTest extends Specification with ShoeboxTestInjector{
  "FailedUriNormalizationRepo" should {
    "work" in {
      val raw = "www.test.com/index"
      val mapped = "www.test.com"
      withDb() { implicit injector =>
        db.readWrite{ implicit s =>
          failedUriNormalizationRepo.createOrIncrease(raw, mapped)
          failedUriNormalizationRepo.createOrIncrease(raw, mapped)
          failedUriNormalizationRepo.createOrIncrease(raw, mapped)
        }
        db.readOnly{ implicit s =>
          val r = failedUriNormalizationRepo.getByUrls(raw, mapped)
          r.get.failedCounts === 3
          r.get.state === FailedUriNormalizationStates.ACTIVE
        }
      }
    }
  }
}

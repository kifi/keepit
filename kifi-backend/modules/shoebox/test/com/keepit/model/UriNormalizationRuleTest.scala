package com.keepit.model

import org.specs2.mutable.Specification
import com.google.inject.Injector
import com.keepit.common.db.Id
import com.keepit.test._

class UriNormalizationRuleTest extends Specification with ShoeboxTestInjector{
  "uriNormalizationRuleRepo" should {
    "work" in {
      val raw = "www.test.com/index"
      val mapped = "www.test.com"
      withDb() { implicit injector =>
        db.readWrite{ implicit s =>
          uriNormalizationRuleRepo.save(UriNormalizationRule(url = raw, mappedUrl = mapped))
        }
        db.readOnly{ implicit s =>
          val r = uriNormalizationRuleRepo.getByUrl(raw)
          r.get === mapped
        }
      }
    }
  }
}
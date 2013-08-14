package com.keepit.model

import org.specs2.mutable.Specification
import com.keepit.test._

class UriNormalizationRuleTest extends Specification with ShoeboxTestInjector{
  "uriNormalizationRuleRepo" should {
    "work" in {
      val raw = "www.test.com/index"
      val mapped = "www.test.com"
      withDb() { implicit injector =>
        db.readWrite{ implicit s =>
          uriNormalizationRuleRepo.save(UriNormalizationRule(prepUrlHash = NormalizedURI.hashUrl(raw), prepUrl = raw, mappedUrl = mapped))
        }
        db.readOnly{ implicit s =>
          val r = uriNormalizationRuleRepo.getByUrl(raw)
          r.get === mapped
        }
      }
    }

    "rule should be unique" in {
      val raw = "www.test.com/index"
      val mapped = "www.test.com"
      val mapped2 = "test.com"
      withDb() { implicit injector =>
        db.readWrite{ implicit s =>
          uriNormalizationRuleRepo.save(UriNormalizationRule(prepUrlHash = NormalizedURI.hashUrl(raw), prepUrl = raw, mappedUrl = mapped))
          uriNormalizationRuleRepo.save(UriNormalizationRule(prepUrlHash = NormalizedURI.hashUrl(raw), prepUrl = raw, mappedUrl = mapped2))
        }
        db.readOnly{ implicit s =>
          val r = uriNormalizationRuleRepo.getByUrl(raw)
          r.get === mapped2
        }
      }
    }
  }
}

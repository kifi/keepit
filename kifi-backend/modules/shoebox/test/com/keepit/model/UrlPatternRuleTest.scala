package com.keepit.model

import com.keepit.common.cache.TransactionalCaching
import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector

class UrlPatternRuleTest extends Specification with ShoeboxTestInjector {

  "UrlPatternRule" should {

    "persist & use patterns w/ appropriate caching" in {
      withDb() { implicit injector =>

        val db = inject[Database]

        val commander = inject[UrlPatternRulesCommander]

        db.readWrite { implicit session =>
          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://www\\.facebook\\.com/login.*$", isUnscrapable = true))
          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://.*.google.com.*/ServiceLogin.*$", isUnscrapable = true))
        }

        db.readOnlyMaster { implicit session =>
          urlPatternRuleRepo.getUrlPatternRules().rules.length === 2
        }

        db.readWrite { implicit session =>
          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://app.asana.com.*$", isUnscrapable = true))
        }

        commander.rules().isUnscrapable("http://www.google.com/") === false
        commander.rules().isUnscrapable("https://www.facebook.com/login.php?bb") === true
      }
    }

    "persist normalization scheme" in {
      withDb() { implicit injector =>
        val d = UrlPatternRule(pattern = """https?://www\.google\.com""", normalization = Some(Normalization.HTTPS))
        val d2 = UrlPatternRule(pattern = "www.baidu.com")

        inject[Database].readWrite { implicit s =>
          val sd = urlPatternRuleRepo.save(d)
          val sd2 = urlPatternRuleRepo.save(d2)
          urlPatternRuleRepo.get(sd.id.get).normalization === Some(Normalization.HTTPS)
          urlPatternRuleRepo.get(sd2.id.get).normalization === None
        }
      }
    }
  }
}

package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.test.ShoeboxTestInjector

class UrlPatternRuleTest extends Specification with ShoeboxTestInjector {

  "Unscrapable" should {

    "persist & use unscrapable patterns w/ appropriate caching" in {
      withDb() { implicit injector =>

        val urlPatternRuleCache = inject[UrlPatternRuleRepoImpl].urlPatternRuleAllCache

        urlPatternRuleCache.get(UrlPatternRuleAllKey()).isDefined === false

        inject[Database].readWrite { implicit session =>
          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://www\\.facebook\\.com/login.*$", isUnscrapable = true))
          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://.*.google.com.*/ServiceLogin.*$", isUnscrapable = true))

          urlPatternRuleCache.get(UrlPatternRuleAllKey()).isDefined === false

          urlPatternRuleRepo.allActive().length === 2
          urlPatternRuleCache.get(UrlPatternRuleAllKey()).isDefined
          urlPatternRuleCache.get(UrlPatternRuleAllKey()).get.length === 2

          urlPatternRuleRepo.save(UrlPatternRule(pattern = "^https*://app.asana.com.*$", isUnscrapable = true))

          urlPatternRuleCache.get(UrlPatternRuleAllKey()).isDefined === false

          urlPatternRuleRepo.isUnscrapable("http://www.google.com/") === false
          urlPatternRuleRepo.isUnscrapable("https://www.facebook.com/login.php?bb") === true
        }
      }
    }

    "persist normalization scheme" in {
      withDb() { implicit injector =>
        val d = UrlPatternRule(pattern = """https?://www\.google\.com""", normalization = Some(Normalization.HTTPS))
        val d2 = UrlPatternRule(pattern = "www.baidu.com")

        inject[Database].readWrite{ implicit s =>
          val sd = urlPatternRuleRepo.save(d)
          val sd2 = urlPatternRuleRepo.save(d2)
          urlPatternRuleRepo.get(sd.id.get).normalization === Some(Normalization.HTTPS)
          urlPatternRuleRepo.get(sd2.id.get).normalization === None
        }
      }
    }
  }
}

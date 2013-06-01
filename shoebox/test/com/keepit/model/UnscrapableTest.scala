package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.{DbRepos, EmptyApplication}

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class UnscrapableTest extends Specification with DbRepos {

  "Unscrapable" should {

    "persist & use unscrapable patterns w/ approperiate cashing" in {
      running(new EmptyApplication()) {

        val unscrapeCache = inject[UnscrapableRepoImpl].unscrapableCache

        unscrapeCache.get(UnscrapableAllKey()).isDefined === false

        inject[Database].readWrite { implicit session =>
          unscrapableRepo.save(Unscrapable(pattern = "^https*://www\\.facebook\\.com/login.*$"))
          unscrapableRepo.save(Unscrapable(pattern = "^https*://.*.google.com.*/ServiceLogin.*$"))

          unscrapeCache.get(UnscrapableAllKey()).isDefined === false

          unscrapableRepo.allActive().length === 2
          Thread sleep 1000
          unscrapeCache.get(UnscrapableAllKey()).get.length === 2

          unscrapableRepo.save(Unscrapable(pattern = "^https*://app.asana.com.*$"))

          unscrapeCache.get(UnscrapableAllKey()).isDefined === false

          unscrapableRepo.contains("http://www.google.com/") === false
          unscrapableRepo.contains("https://www.facebook.com/login.php?bb") === true
        }
      }
    }
  }
}

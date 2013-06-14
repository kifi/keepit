package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class URLPatternTest extends Specification with DbRepos {
  "URLPattern" should {
    "save, load by id and pattern" in {
      running(new EmptyApplication()) {
        val repo = inject[URLPatternRepo]
        inject[URLPatternRepo] must be(repo) // singleton

        val (p1, p2) = inject[Database].readWrite{ implicit session =>
          (repo.save(URLPattern(None, """^https?://www\.42go\.com""", None)),
           repo.save(URLPattern(None, """://(www\.|)hulu\.com/watch/""", None)))
        }

        inject[Database].readOnly{ implicit session =>
          repo.get(p1.id.get) === p1
          repo.get(p2.id.get) === p2
          repo.get(p1.pattern) === Some(p1)
          repo.get(p2.pattern) === Some(p2)
          repo.getActivePatterns.length must be_>(0)
        }
      }
    }
  }
}

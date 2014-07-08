package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.test._

class URLPatternTest extends Specification with ShoeboxTestInjector {
  "URLPattern" should {
    "save, load by id and pattern" in {
      withDb() { implicit injector =>
        val repo = inject[URLPatternRepo]
        inject[URLPatternRepo] must be(repo) // singleton

        val (p1, p2) = inject[Database].readWrite{ implicit session =>
          (repo.save(URLPattern(None, """^https?://www\.42go\.com""", None)),
           repo.save(URLPattern(None, """://(www\.|)hulu\.com/watch/""", None)))
        }

        inject[Database].readOnlyMaster{ implicit session =>
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

package com.keepit.classify

import org.specs2.mutable._

import com.keepit.common.db.slick.Database
import com.keepit.inject.inject
import com.keepit.test.DbRepos
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test.Helpers._


class DomainTagTest extends Specification with DbRepos {
  "The tag repo" should {
    "save and retrieve tags" in {
      running(new EmptyApplication()) {
        val tagRepo = inject[DomainTagRepo]

        val t1 = DomainTag(name = DomainTagName("tag1"), sensitive = Some(true))
        val t2 = DomainTag(name = DomainTagName("tag2"), sensitive = Some(false))
        val t3 = DomainTag(name = DomainTagName("tag3"), sensitive = None)

        inject[Database].readWrite { implicit c =>
          val Seq(st1, st2, st3) = Seq(t1, t2, t3).map(tagRepo.save(_))

          tagRepo.get(st1.id.get) === st1
          tagRepo.get(st2.id.get) === st2
          tagRepo.get(st3.id.get) === st3

          tagRepo.save(st1.withSensitive(Some(false)))
          tagRepo.get(st2.id.get).sensitive.get === false

          tagRepo.save(st2.withSensitive(None))
          tagRepo.get(st2.id.get).sensitive === None
        }
      }
    }
  }
}

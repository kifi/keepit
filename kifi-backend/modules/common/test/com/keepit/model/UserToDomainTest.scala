package com.keepit.model

import org.joda.time.DateTime
import org.specs2.mutable._

import com.keepit.classify.{Domain, DomainRepo}
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.test._

import play.api.Play.current
import play.api.libs.json._
import play.api.test._
import play.api.test.Helpers._

class UserToDomainTest extends Specification with DbRepos {
  "UserToDomain" should {
    "save and load" in {
      running(new EmptyApplication()) {
        val repo = inject[UserToDomainRepo]
        inject[UserToDomainRepo] must be(repo) // singleton

        val (u1, u2, d1, d2, u1d1, u1d2, u2d2) = inject[Database].readWrite{ implicit session =>
          val u1 = inject[UserRepo].save(User(firstName = "J", lastName = "Jacobs"))
          val u2 = inject[UserRepo].save(User(firstName = "A", lastName = "Jacobs"))
          val d1 = inject[DomainRepo].save(Domain(hostname = "outlook.com"))
          val d2 = inject[DomainRepo].save(Domain(hostname = "audible.com"))
          (u1, u2, d1, d2,
           repo.save(UserToDomain(None, u1.id.get, d1.id.get, UserToDomainKinds.NEVER_SHOW, None)),
           repo.save(UserToDomain(None, u1.id.get, d2.id.get, UserToDomainKinds.NEVER_SHOW, None)),
           repo.save(UserToDomain(None, u2.id.get, d2.id.get, UserToDomainKinds.NEVER_SHOW, None)))
        }

        inject[Database].readOnly{ implicit session =>
          repo.get(u1.id.get, d1.id.get, UserToDomainKinds.NEVER_SHOW) === Some(u1d1)
          repo.get(u1.id.get, d2.id.get, UserToDomainKinds.NEVER_SHOW) === Some(u1d2)
          repo.get(u2.id.get, d1.id.get, UserToDomainKinds.NEVER_SHOW) === None
          repo.get(u2.id.get, d2.id.get, UserToDomainKinds.NEVER_SHOW) === Some(u2d2)
        }
      }
    }
  }
}

package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class FollowTest extends SpecificationWithJUnit {

  "Follow" should {

    "load by user and uri" in {
      running(new EmptyApplication()) {
        val repo = inject[Repo[Follow]]
        val followRepo = inject[FollowRepo]

        inject[DBConnection].readWrite{ implicit session =>
          repo.count === 0
        }

        val (user1, user2, uriA, uriB) = CX.withConnection { implicit c =>
          (User(firstName = "User", lastName = "1").save,
           User(firstName = "User", lastName = "2").save,
           NormalizedURI("Google", "http://www.google.com/").save,
           NormalizedURI("Amazon", "http://www.amazon.com/").save)
        }

        inject[DBConnection].readOnly{ implicit session =>
          followRepo.get(user1.id.get, uriB.id.get).isDefined === false
          followRepo.get(user2.id.get, uriA.id.get).isDefined === false
        }

        val (f1, f2) = inject[DBConnection].readWrite{ implicit session =>
          val f1 = repo.save(Follow(userId = user1.id.get, uriId = uriB.id.get))
          val f2 = repo.save(Follow(userId = user2.id.get, uriId = uriA.id.get, state = FollowStates.INACTIVE))
          (f1, f2)
        }

        inject[DBConnection].readOnly{ implicit session =>
          repo.get(f1.id.get) === f1
          repo.get(f2.id.get) === f2
          repo.all.size === 2
          followRepo.all(user1.id.get).size === 1
          followRepo.all(user2.id.get).size === 0 //inactive
          followRepo.all(user1.id.get).head === f1

          followRepo.get(user1.id.get, uriA.id.get).isDefined === false
          followRepo.get(user1.id.get, uriB.id.get).isDefined === true
          followRepo.get(user2.id.get, uriA.id.get).isDefined === false
          followRepo.get(user2.id.get, uriB.id.get).isDefined === false
        }

        inject[DBConnection].readWrite{ implicit session =>
          repo.save(f1.deactivate)
          repo.save(f2.activate)
        }

        inject[DBConnection].readOnly{ implicit session =>
          followRepo.get(user1.id.get, uriA.id.get).isDefined === false
          followRepo.get(user1.id.get, uriB.id.get).isDefined === false
          followRepo.get(user2.id.get, uriA.id.get).isDefined === true
          followRepo.get(user2.id.get, uriB.id.get).isDefined === false
        }
      }
    }
  }
}

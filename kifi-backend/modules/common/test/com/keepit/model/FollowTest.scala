package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.slick._
import com.keepit.inject._
import com.keepit.test._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class FollowTest extends Specification with DbRepos {

  "Follow" should {

    "load by user and uri" in {
      running(new EmptyApplication()) {
        val repo = inject[FollowRepo]
        repo.eq(inject[FollowRepo]) === true //verify singleton

        inject[Database].readWrite{ implicit session =>
          repo.count === 0
        }

        val (user1, user2, uriA, uriB) = inject[Database].readWrite{ implicit session =>
          (userRepo.save(User(firstName = "User", lastName = "1")),
           userRepo.save(User(firstName = "User", lastName = "2")),
           uriRepo.save(NormalizedURIFactory("Google", "http://www.google.com/")),
           uriRepo.save(NormalizedURIFactory("Amazon", "http://www.amazon.com/")))
        }

        inject[Database].readOnly{ implicit session =>
          repo.get(user1.id.get, uriB.id.get).isDefined === false
          repo.get(user2.id.get, uriA.id.get).isDefined === false
        }

        val (f1, f2) = inject[Database].readWrite{ implicit session =>
          val f1 = repo.save(Follow(userId = user1.id.get, uriId = uriB.id.get))
          val f2 = repo.save(Follow(userId = user2.id.get, uriId = uriA.id.get, state = FollowStates.INACTIVE))
          (f1, f2)
        }

        inject[Database].readOnly{ implicit session =>
          repo.get(f1.id.get) === f1
          repo.get(f2.id.get) === f2
          repo.all().size === 2
          repo.getByUser(user1.id.get).size === 1
          repo.getByUser(user2.id.get).size === 0 //inactive
          repo.getByUser(user1.id.get).head === f1

          repo.get(user1.id.get, uriA.id.get).isDefined === false
          repo.get(user1.id.get, uriB.id.get).isDefined === true
          repo.get(user2.id.get, uriA.id.get).isDefined === false
          repo.get(user2.id.get, uriB.id.get).isDefined === false
        }

        inject[Database].readWrite{ implicit session =>
          repo.save(f1.deactivate)
          repo.save(f2.activate)
        }

        inject[Database].readOnly{ implicit session =>
          repo.get(user1.id.get, uriA.id.get).isDefined === false
          repo.get(user1.id.get, uriB.id.get).isDefined === false
          repo.get(user2.id.get, uriA.id.get).isDefined === true
          repo.get(user2.id.get, uriB.id.get).isDefined === false
        }
      }
    }
  }
}

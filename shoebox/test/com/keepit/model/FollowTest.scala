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

        inject[DBConnection].readWrite{ implicit session =>
          inject[Repo[Follow]].count === 0
        }

        val (user1, user2, uriA, uriB) = CX.withConnection { implicit c =>
          (User(firstName = "User", lastName = "1").save,
           User(firstName = "User", lastName = "2").save,
           NormalizedURI("Google", "http://www.google.com/").save,
           NormalizedURI("Amazon", "http://www.amazon.com/").save)
        }

        CX.withConnection { implicit c =>
          FollowCxRepo.get(user1.id.get, uriB.id.get).isDefined === false
          FollowCxRepo.get(user2.id.get, uriA.id.get).isDefined === false
        }

        inject[DBConnection].readWrite{ implicit session =>
          val repo = inject[Repo[Follow]]
          repo.save(Follow(userId = user1.id.get, uriId = uriB.id.get))
          repo.save(Follow(userId = user2.id.get, uriId = uriA.id.get, state = FollowStates.INACTIVE))
        }

        CX.withConnection { implicit c =>
          FollowCxRepo.get(user1.id.get, uriA.id.get).isDefined === false
          FollowCxRepo.get(user1.id.get, uriB.id.get).isDefined === true
          FollowCxRepo.get(user2.id.get, uriA.id.get).isDefined === true
          FollowCxRepo.get(user2.id.get, uriB.id.get).isDefined === false
        }
      }
    }
  }

}

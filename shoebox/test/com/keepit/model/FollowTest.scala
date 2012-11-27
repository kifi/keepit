package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db.CX
import com.keepit.common.db.CX._
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class FollowTest extends SpecificationWithJUnit {

  "Follow" should {

    "load by user and uri" in {
      running(new EmptyApplication()) {

        val (user1, user2, uriA, uriB) = CX.withConnection { implicit c =>
          (User(firstName = "User", lastName = "1").save,
           User(firstName = "User", lastName = "2").save,
           NormalizedURI("Google", "http://www.google.com/").save,
           NormalizedURI("Amazon", "http://www.amazon.com/").save)
        }

        CX.withConnection { implicit c =>
          Follow(userId = user1.id.get, uriId = uriB.id.get).save
          Follow(userId = user2.id.get, uriId = uriA.id.get, state = Follow.States.INACTIVE).save
        }

        CX.withConnection { implicit c =>
          Follow.get(user1.id.get, uriA.id.get).isDefined === false
          Follow.get(user1.id.get, uriB.id.get).isDefined === true
          Follow.get(user2.id.get, uriA.id.get).isDefined === true
          Follow.get(user2.id.get, uriB.id.get).isDefined === false
        }
      }
    }
  }

}

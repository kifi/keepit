package com.keepit.model

import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.test._
import com.keepit.inject._
import securesocial.core._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import com.keepit.common.cache.{FortyTwoCachePlugin, FortyTwoCache}
import com.keepit.common.db.slick.Database
import com.keepit.common.db._

@RunWith(classOf[JUnitRunner])
class UserRepoTest extends SpecificationWithJUnit with DbRepos {

  "UserRepo" should {
    "Use the cache" in {
      running(new EmptyApplication()) {

        val userRepo = inject[UserRepoImpl]
        userRepo.idCache.get(UserIdKey(Id[User](1))).isDefined === false

        inject[Database].readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          userRepo.idCache.get(UserIdKey(Id[User](1))).get === user

          val updatedUser = userRepo.save(user.copy(lastName = "NotMyLastName"))

          userRepo.idCache.get(UserIdKey(Id[User](1))).get !== user
          userRepo.idCache.get(UserIdKey(Id[User](1))).get === updatedUser
        }
      }
    }
  }


}

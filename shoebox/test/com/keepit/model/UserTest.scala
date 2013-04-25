package com.keepit.model

import org.specs2.mutable._

import com.keepit.test._
import com.keepit.inject._
import securesocial.core._

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._
import com.keepit.common.cache.{FortyTwoCachePlugin, FortyTwoCache}
import com.keepit.common.db.slick.Database
import com.keepit.common.db._

class UserRepoTest extends Specification with TestDBRunner {

  "UserRepo" should {
    "Use the cache" in {
      withDB() { implicit injector =>

      val userRepoImpl = userRepo.asInstanceOf[UserRepoImpl]
        inject[Database].readWrite { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).isDefined === false
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === user

          val updatedUser = userRepo.save(user.copy(lastName = "NotMyLastName"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get !== user
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === updatedUser
        }
      }
    }
  }


}

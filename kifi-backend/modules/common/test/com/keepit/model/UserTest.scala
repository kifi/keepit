package com.keepit.model

import scala.util.Try

import org.specs2.mutable._

import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.test._

class UserRepoTest extends Specification with TestDBRunner {

  "UserRepo" should {
    "Use the cache" in {
      withDB() { implicit injector =>
        val userRepoImpl = userRepo.asInstanceOf[UserRepoImpl]
        val sessionProvider = inject[TestSlickSessionProvider]
        inject[Database].readWrite { implicit session =>
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).isDefined === false
          val user = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === user
          val updatedUser = userRepo.save(user.copy(lastName = "NotMyLastName"))

          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get !== user
          userRepoImpl.idCache.get(UserIdKey(Id[User](1))).get === updatedUser
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnly { implicit s => userRepoImpl.get(Id[User](1)) }
        }
        Try(db.readOnly { implicit s => userRepoImpl.get(Id[User](2)) })
        sessionProvider.readOnlySessionsCreated === 1
      }
    }
  }


}

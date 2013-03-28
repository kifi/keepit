package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db._
import com.keepit.common.time.zones.PT
import com.keepit.test._
import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.inject._

import play.api.Play.current
import play.api.test.Helpers._

class UserValueTest extends Specification with DbRepos {

  "UserValue" should {
    "create, update, delete using the cache (and invalidate properly)" in {
      running(new EmptyApplication()) {
        val userValueRepo = inject[UserValueRepoImpl]
        userValueRepo.valueCache.get(UserValueKey(Id[User](1), "test")).isDefined === false

        db.readWrite { implicit session =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
          userValueRepo.getValue(user1.id.get, "test").isDefined === false

          val uv = userValueRepo.save(UserValue(userId = user1.id.get, name = "test", value = "this right here!"))
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).isDefined === false
          userValueRepo.getValue(user1.id.get, "test").isDefined === true
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).get === "this right here!"

          userValueRepo.save(userValueRepo.get(uv.id.get).withState(UserValueStates.INACTIVE))
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).isDefined === false
        }
      }
    }
  }
}

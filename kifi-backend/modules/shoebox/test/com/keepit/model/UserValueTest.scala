package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db._
import com.keepit.test._
import com.keepit.model.UserValues.{ UserValueIntHandler, UserValueStringHandler }

class UserValueTest extends Specification with ShoeboxTestInjector {

  "UserValue" should {
    "create, update, delete using the cache (and invalidate properly)" in {
      withDb() { implicit injector =>
        val userValueRepo = inject[UserValueRepoImpl]
        val test = UserValueStringHandler("test", "some default value")
        val test1 = UserValueIntHandler("test1", -1000)

        val (user1, uv) = db.readWrite { implicit session =>
          userValueRepo.valueCache.get(UserValueKey(Id[User](1), "test")).isDefined === false
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))
          userValueRepo.getValue(user1.id.get, test) === test.default

          val uv = userValueRepo.save(UserValue(userId = user1.id.get, name = "test", value = "this right here!"))

          (user1, uv)
        }

        db.readOnlyMaster { implicit s =>
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).isDefined === false
          userValueRepo.getValue(user1.id.get, test) === "this right here!"
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).get === "this right here!"
        }
        db.readWrite { implicit s =>
          userValueRepo.save(userValueRepo.get(uv.id.get).withState(UserValueStates.INACTIVE))
        }
        db.readOnlyMaster { implicit s =>
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, "test")).isDefined === false
        }

        db.readWrite { implicit s =>
          userValueRepo.save(uv.withState(UserValueStates.ACTIVE))
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s => userValueRepo.getValue(user1.id.get, test1) }
        } should throwAn[IllegalStateException]

        db.readOnlyMaster { implicit s => userValueRepo.getValue(user1.id.get, test) } === "this right here!"
        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s => userValueRepo.getValue(user1.id.get, test) } === "this right here!"
        }

        db.readOnlyMaster { implicit s =>
          userValueRepo.getValues(user1.id.get, "test", "test1")
        } === Map("test" -> Some("this right here!"), "test1" -> None)

        db.readWrite { implicit s =>
          userValueRepo.save(UserValue(userId = user1.id.get, name = "test2", value = "this right there!"))
        }
        db.readOnlyMaster { implicit s =>
          userValueRepo.getValues(user1.id.get, "test", "test1", "test2")
        } === Map(
          "test" -> Some("this right here!"),
          "test1" -> None,
          "test2" -> Some("this right there!")
        )
      }
    }
  }
}

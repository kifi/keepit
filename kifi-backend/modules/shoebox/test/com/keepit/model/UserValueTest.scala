package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db._
import com.keepit.test._
import com.keepit.model.UserValues.{ UserValueIntHandler, UserValueStringHandler }
import com.keepit.model.UserFactoryHelper._

class UserValueTest extends Specification with ShoeboxTestInjector {

  "UserValue" should {
    "create, update, delete using the cache (and invalidate properly)" in {
      withDb() { implicit injector =>
        val userValueRepo = inject[UserValueRepoImpl]
        val userValueNameTest = UserValueName("test")
        val userValueNameTest1 = UserValueName("test1")
        val userValueNameTest2 = UserValueName("test2")
        val test = UserValueStringHandler(userValueNameTest, "some default value")
        val test1 = UserValueIntHandler(userValueNameTest1, -1000)

        val (user1, uv) = db.readWrite { implicit session =>
          userValueRepo.valueCache.get(UserValueKey(Id[User](1), userValueNameTest)).isDefined === false
          val user1 = UserFactory.user().withName("Andrew", "Conner").withUsername("test").saved
          userValueRepo.getValue(user1.id.get, test) === test.default

          val uv = userValueRepo.save(UserValue(userId = user1.id.get, name = userValueNameTest, value = "this right here!"))

          (user1, uv)
        }

        db.readOnlyMaster { implicit s =>
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, userValueNameTest)).isDefined === false
          userValueRepo.getValue(user1.id.get, test) === "this right here!"
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, userValueNameTest)).get === "this right here!"
        }
        db.readWrite { implicit s =>
          userValueRepo.save(userValueRepo.get(uv.id.get).withState(UserValueStates.INACTIVE))
        }
        db.readOnlyMaster { implicit s =>
          userValueRepo.valueCache.get(UserValueKey(user1.id.get, userValueNameTest)).isDefined === false
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
          userValueRepo.getValues(user1.id.get, userValueNameTest, userValueNameTest1)
        } === Map(userValueNameTest -> Some("this right here!"), userValueNameTest1 -> None)

        db.readWrite { implicit s =>
          userValueRepo.save(UserValue(userId = user1.id.get, name = userValueNameTest2, value = "this right there!"))
        }
        db.readOnlyMaster { implicit s =>
          userValueRepo.getValues(user1.id.get, userValueNameTest, userValueNameTest1, userValueNameTest2)
        } === Map(
          userValueNameTest -> Some("this right here!"),
          userValueNameTest1 -> None,
          userValueNameTest2 -> Some("this right there!")
        )
      }
    }
  }
}

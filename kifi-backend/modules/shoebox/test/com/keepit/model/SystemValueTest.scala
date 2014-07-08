package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db._
import com.keepit.test._

class SystemValueTest extends Specification with ShoeboxTestInjector {

  "SystemValue" should {
    "create, update, delete using the cache (and invalidate properly)" in {
      withDb() { implicit injector =>
        val systemValueRepo = inject[SystemValueRepoImpl]

        val (test, value) = db.readWrite { implicit session =>
          val name = Name[SystemValue]("test")
          systemValueRepo.valueCache.get(SystemValueKey(name)).isDefined === false
          systemValueRepo.getValue(name).isDefined === false

          val value = systemValueRepo.save(SystemValue(name = name, value = "this right here!"))

          (name, value)
        }

        db.readOnlyMaster { implicit s =>
          systemValueRepo.getValue(test).isDefined === true
          // systemValueRepo.valueCache.get(SystemValueKey(test)).get === "this right here!"
        }
        db.readWrite { implicit s =>
          systemValueRepo.save(systemValueRepo.get(value.id.get).withState(SystemValueStates.INACTIVE))
        }
        db.readOnlyMaster { implicit s =>
          systemValueRepo.valueCache.get(SystemValueKey(test)).isDefined === false
        }

        db.readWrite { implicit s =>
          systemValueRepo.save(value.withState(SystemValueStates.ACTIVE))
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s => systemValueRepo.getValue(Name("test1")) }
        } should throwAn[IllegalStateException]

        db.readOnlyMaster { implicit s => systemValueRepo.getValue(test) } === Some("this right here!")
        // sessionProvider.doWithoutCreatingSessions {
        //   db.readOnly { implicit s => systemValueRepo.getValue(test) } === Some("this right here!")
        // }
      }
    }
  }
}

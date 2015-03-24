package com.keepit.model

import org.specs2.mutable._

import com.keepit.test._

class SystemValueTest extends Specification with ShoeboxTestInjector {

  "SystemValue" should {
    "create, update, delete" in {
      withDb() { implicit injector =>
        val systemValueRepo = inject[SystemValueRepo]

        val (test, value) = db.readWrite { implicit session =>
          val name = Name[SystemValue]("test")
          systemValueRepo.getValue(name).isDefined === false

          val value = systemValueRepo.save(SystemValue(name = name, value = "this right here!"))

          (name, value)
        }

        db.readOnlyMaster { implicit s =>
          systemValueRepo.getValue(test).isDefined === true
        }
        db.readWrite { implicit s =>
          systemValueRepo.save(systemValueRepo.get(value.id.get).withState(SystemValueStates.INACTIVE))
        }

        db.readWrite { implicit s =>
          systemValueRepo.save(value.withState(SystemValueStates.ACTIVE))
        }

        sessionProvider.doWithoutCreatingSessions {
          db.readOnlyMaster { implicit s => systemValueRepo.getValue(Name("test1")) }
        } should throwAn[IllegalStateException]

        db.readOnlyMaster { implicit s => systemValueRepo.getValue(test) } === Some("this right here!")
      }
    }
  }
}

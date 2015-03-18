package com.keepit.commanders

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.model.ActivityPushTaskRepo
import com.keepit.shoebox.cron.ActivityPusher
import com.keepit.test.ShoeboxTestInjector

import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import org.joda.time.LocalTime
import org.specs2.mutable.Specification

class ActivityPushTest extends Specification with ShoeboxTestInjector {

  def modules = FakeExecutionContextModule() :: FakeElizaServiceClientModule() :: Nil

  "ActivityPushSchedualer" should {
    "create tasks" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3) = db.readWrite { implicit rw =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          user().saved
          user().saved
          user().saved
          val lib1 = library().withUser(user1).saved
          keep().withLibrary(lib1).saved
          (user1, user2, user3)
        }
        inject[ActivityPusher].createPushActivityEntities()
        db.readOnlyMaster { implicit s =>
          val repo = inject[ActivityPushTaskRepo]
          repo.getByUser(user1.id.get).get.userId === user1.id.get
          repo.getByUser(user2.id.get).get.userId === user2.id.get
          repo.getByUser(user3.id.get).get.userId === user3.id.get
          repo.all().size === 6
          repo.getByPushAndActivity(END_OF_TIME, new LocalTime(0, 0, 0), 10).size === 0
        }
      }
    }
  }
}

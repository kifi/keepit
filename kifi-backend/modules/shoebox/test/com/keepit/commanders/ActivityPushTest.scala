package com.keepit.commanders

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.UserAgent
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.model._
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
        val repo = inject[ActivityPushTaskRepo]
        val (user1, user2, user3) = db.readWrite { implicit rw =>
          val user1 = user().saved
          val user2 = user().saved
          val user3 = user().saved
          val kifiInstallationRepo = inject[KifiInstallationRepo]
          kifiInstallationRepo.save(KifiInstallation(userId = user1.id.get,
            version = KifiIPhoneVersion("1.1.1"), externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"), platform = KifiInstallationPlatform.IPhone))
          kifiInstallationRepo.save(KifiInstallation(userId = user2.id.get,
            version = KifiIPhoneVersion("1.1.1"), externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"), platform = KifiInstallationPlatform.IPhone))
          kifiInstallationRepo.save(KifiInstallation(userId = user3.id.get,
            version = KifiIPhoneVersion("1.1.1"), externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"), platform = KifiInstallationPlatform.IPhone))
          user().saved
          user().saved
          user().saved
          val lib1 = library().withUser(user1).saved
          keep().withLibrary(lib1).saved
          repo.all().size === 0
          repo.getByUser(user1.id.get) === None
          (user1, user2, user3)
        }
        inject[ActivityPusher].createPushActivityEntities(2)
        db.readOnlyMaster { implicit s =>
          repo.getByUser(user1.id.get).get.userId === user1.id.get
          repo.getByUser(user2.id.get).get.userId === user2.id.get
          repo.getByUser(user3.id.get).get.userId === user3.id.get
          repo.all().size === 3
          repo.getBatchToPush(10).size === 0
        }
      }
    }
  }
}

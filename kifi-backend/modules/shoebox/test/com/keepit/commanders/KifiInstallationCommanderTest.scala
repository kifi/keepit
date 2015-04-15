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

class KifiInstallationCommanderTest extends Specification with ShoeboxTestInjector {

  def modules = Nil

  "KifiInstallationCommanderTest" should {
    "canSendPushForLibraries iPhone" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KifiInstallationCommander]
        db.readWrite { implicit s =>
          inject[KifiInstallationRepo].save(KifiInstallation(
            userId = Id[User](1),
            version = KifiIPhoneVersion("2.1.0"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"),
            platform = KifiInstallationPlatform.IPhone))
        }
        inject[KifiInstallationCommander].
        pusher.canSendPushForLibraries() === true
        pusher.canSendPushForLibraries(KifiInstallation(
          userId = Id[User](1),
          version = KifiIPhoneVersion("1.1.0"),
          externalId = ExternalId[KifiInstallation](),
          userAgent = UserAgent("my iphone"),
          platform = KifiInstallationPlatform.IPhone)) === false
        pusher.canSendPushForLibraries(KifiInstallation(
          userId = Id[User](1),
          version = KifiIPhoneVersion("2.2.0"),
          externalId = ExternalId[KifiInstallation](),
          userAgent = UserAgent("my iphone"),
          platform = KifiInstallationPlatform.IPhone)) === true
      }
    }
    "canSendPushForLibraries Android" in {
      withDb(modules: _*) { implicit injector =>
        val commander = inject[KifiInstallationCommander]
        pusher.canSendPushForLibraries(KifiInstallation(
          userId = Id[User](1),
          version = KifiAndroidVersion("2.2.4"),
          externalId = ExternalId[KifiInstallation](),
          userAgent = UserAgent("my Android"),
          platform = KifiInstallationPlatform.Android)) === true
        pusher.canSendPushForLibraries(KifiInstallation(
          userId = Id[User](1),
          version = KifiAndroidVersion("1.1.0"),
          externalId = ExternalId[KifiInstallation](),
          userAgent = UserAgent("my Android"),
          platform = KifiInstallationPlatform.Android)) === false
        pusher.canSendPushForLibraries(KifiInstallation(
          userId = Id[User](1),
          version = KifiAndroidVersion("2.3.0"),
          externalId = ExternalId[KifiInstallation](),
          userAgent = UserAgent("my Android"),
          platform = KifiInstallationPlatform.Android)) === true
      }
    }
  }
}

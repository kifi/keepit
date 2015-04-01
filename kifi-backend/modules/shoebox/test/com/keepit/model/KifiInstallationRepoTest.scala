package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.net.UserAgent
import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.test._
import org.joda.time.DateTime

class KifiInstallationRepoTest extends Specification with ShoeboxTestInjector {

  "KifiInstallationRepo" should {
    "persist" in {
      withDb() { implicit injector =>
        val (user, installExt) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Dafna", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val installExt = installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiExtVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my browser"),
            platform = KifiInstallationPlatform.Extension))
          (user, installExt)
        }

        db.readOnlyMaster { implicit s =>
          installationRepo.get(installExt.id.get) === installExt
          val all = installationRepo.all(user.id.get)
          all.size === 1
          all.head === installExt
          installationRepo.getOpt(user.id.get, installExt.externalId) === Some(installExt)
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 1
        }

        db.readWrite { implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiExtVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my other browser"),
            platform = KifiInstallationPlatform.Extension))
        }

        db.readOnlyMaster { implicit s =>
          val all = installationRepo.all(user.id.get)
          all.size === 2
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 2
        }

        val installIphone = db.readWrite { implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiIPhoneVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"),
            platform = KifiInstallationPlatform.IPhone))
        }

        val installAndroid = db.readWrite { implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiAndroidVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my android"),
            platform = KifiInstallationPlatform.Android))
        }

        installIphone.platform === KifiInstallationPlatform.IPhone
        installAndroid.platform === KifiInstallationPlatform.Android
        installExt.platform === KifiInstallationPlatform.Extension

        //we're not mixing iphone and extension platforms!
        db.readOnlyMaster { implicit s =>
          val all = installationRepo.all(user.id.get)
          all.size === 4
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 2
        }

      }
    }

    "last update" in {
      withDb(FakeClockModule()) { implicit injector =>
        val now = new DateTime(2015, 1, 1, 1, 1, 1, DEFAULT_DATE_TIME_ZONE)
        val clock = inject[FakeClock]
        val (user1, user2, kin) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Dafna", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "Shanee", lastName = "Smith", username = Username("test2"), normalizedUsername = "test2"))
          clock.setTimeValue(now.minusDays(4))
          installationRepo.save(KifiInstallation(
            userId = user1.id.get,
            version = KifiExtVersion("1.1.0"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my browser"),
            platform = KifiInstallationPlatform.Extension))
          clock.setTimeValue(now)
          installationRepo.save(KifiInstallation(
            userId = user1.id.get,
            version = KifiIPhoneVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my phone"),
            platform = KifiInstallationPlatform.IPhone))
          clock.setTimeValue(now.plusDays(2))
          val kin = installationRepo.save(KifiInstallation(
            userId = user1.id.get,
            version = KifiIPhoneVersion("1.1.2"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my other phone"),
            platform = KifiInstallationPlatform.IPhone))
          clock.setTimeValue(now.plusDays(4))
          installationRepo.save(KifiInstallation(
            userId = user1.id.get,
            version = KifiExtVersion("1.1.5"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my browser"),
            platform = KifiInstallationPlatform.Extension))
          (user1, user2, kin)
        }
        db.readOnlyMaster { implicit s =>
          inject[KifiInstallationRepo].lastUpdatedMobile(user2.id.get) === None
          inject[KifiInstallationRepo].lastUpdatedMobile(user1.id.get) === Some(kin)
        }
      }
    }
  }

}

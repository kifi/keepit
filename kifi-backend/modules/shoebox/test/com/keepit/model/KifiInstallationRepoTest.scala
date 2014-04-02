package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.net.UserAgent
import com.keepit.common.db._
import com.keepit.test._
import org.joda.time.DateTime

class KifiInstallationRepoTest extends Specification with ShoeboxTestInjector {

  "KifiInstallationRepo" should {
    "persist" in {
      withDb() { implicit injector =>
        val (user, installExt) = db.readWrite {implicit s =>
          val user = userRepo.save(User(firstName = "Dafna", lastName = "Smith"))
          val installExt = installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiExtVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent.fromString("my browser"),
            platform = KifiInstallationPlatform.Extension))
          (user, installExt)
        }

        db.readOnly {implicit s =>
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

        db.readWrite {implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiExtVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent.fromString("my other browser"),
            platform = KifiInstallationPlatform.Extension))
        }

        db.readOnly {implicit s =>
          val all = installationRepo.all(user.id.get)
          all.size === 2
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 2
        }

        val installIphone = db.readWrite {implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiIPhoneVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent.fromString("my iphone"),
            platform = KifiInstallationPlatform.IPhone))
        }

        installIphone.platform === KifiInstallationPlatform.IPhone
        installExt.platform === KifiInstallationPlatform.Extension

        //we're not mixing iphone and extension platforms!
        db.readOnly { implicit s =>
          val all = installationRepo.all(user.id.get)
          all.size === 3
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 2
        }

      }
    }
  }

}

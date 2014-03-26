package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.net.UserAgent
import com.keepit.common.db._
import com.keepit.test._
import org.joda.time.DateTime

class KifiInstallationTest extends Specification with ShoeboxTestInjector {

  "KifiInstallation" should {
    "parse version strings and order correctly" in {
      val v0 = KifiVersion.extVersion("0.0.0")
      val v1 = KifiVersion.extVersion("2.1.0")
      val v2 = KifiVersion.extVersion("2.1.0")
      val v3 = KifiVersion.extVersion("3.0.1")
      val v4 = KifiVersion.extVersion("2.4.8")

      v0 must be_<  (v1)
      v1 must be_== (v1)
      v1 must be_== (v2)
      v1 must be_<  (v3)
      v3 must be_>  (v4)
      v4 must be_>  (v2)
    }
    "fail to parse an invalid version string" in {
      KifiVersion.extVersion("foo") must throwA[Exception]
    }
    "persist" in {
      withDb() { implicit injector =>
        val (user, install) = db.readWrite {implicit s =>
          val user = userRepo.save(User(firstName = "Dafna", lastName = "Smith"))
          val install = installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiVersion.extVersion("1.1.1"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent.fromString("my browser"),
            platform = KifiInstallationPlatform.Extension))
          (user, install)
        }

        db.readOnly {implicit s =>
          installationRepo.get(install.id.get) === install
          val all = installationRepo.all(user.id.get)
          all.size === 1
          all.head === install
          installationRepo.getOpt(user.id.get, install.externalId) === Some(install)
          val versions = installationRepo.getLatestActiveExtensionVersions(20)
          versions.size === 1
          versions.head._1 === KifiExtVersion(1, 1, 1)
          versions.head._3 === 1
        }

        db.readWrite {implicit s =>
          installationRepo.save(KifiInstallation(
            userId = user.id.get,
            version = KifiVersion.extVersion("1.1.1"),
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
      }
    }
  }
}

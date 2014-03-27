package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.net.UserAgent
import com.keepit.common.db._
import com.keepit.test._
import org.joda.time.DateTime

class KifiInstallationTest extends Specification with ShoeboxTestInjector {

  "KifiInstallation" should {
    "parse version strings and order correctly for extension" in {
      val v0 = KifiExtVersion("0.0.0")
      val v1 = KifiExtVersion("2.1.0")
      val v2 = KifiExtVersion("2.1.0")
      val v3 = KifiExtVersion("3.0.1")
      val v4 = KifiExtVersion("2.4.8")

      v0 must be_<  (v1)
      v1 must be_== (v1)
      v1 must be_== (v2)
      v1 must be_<  (v3)
      v3 must be_>  (v4)
      v4 must be_>  (v2)
    }
    "parse version strings and order correctly for iphone" in {
      val v0 = KifiIPhoneVersion("0.0.0")
      val v1 = KifiIPhoneVersion("2.1.0")
      val v2 = KifiIPhoneVersion("2.1.0")
      val v3 = KifiIPhoneVersion("3.0.1")
      val v4 = KifiIPhoneVersion("2.4.8")

      v0 must be_<  (v1)
      v1 must be_== (v1)
      v1 must be_== (v2)
      v1 must be_<  (v3)
      v3 must be_>  (v4)
      v4 must be_>  (v2)

      //KifiIPhoneVersion("2.4.8") > KifiExtVersion("1.4.8") YO! this does not compile
    }
    "fail to parse an invalid version string" in {
      KifiExtVersion("foo") must throwA[Exception]
    }

    "won't mix platforms" in {
      KifiInstallation(
        userId = Id[User](1),
        version = KifiIPhoneVersion("1.1.1"),
        externalId = ExternalId[KifiInstallation](),
        userAgent = UserAgent.fromString("my iphone"),
        platform = KifiInstallationPlatform.IPhone) //cool!

      KifiInstallation(
        userId = Id[User](1),
        version = KifiIPhoneVersion("1.1.1"),
        externalId = ExternalId[KifiInstallation](),
        userAgent = UserAgent.fromString("my iphone"),
        platform = KifiInstallationPlatform.Extension) should throwA[Exception]

      KifiInstallation(
        userId = Id[User](1),
        version = KifiExtVersion("1.1.1"),
        externalId = ExternalId[KifiInstallation](),
        userAgent = UserAgent.fromString("my iphone"),
        platform = KifiInstallationPlatform.IPhone) should throwA[Exception]

    }

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

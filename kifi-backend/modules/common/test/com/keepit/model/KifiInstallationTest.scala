package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.net.UserAgent
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.test._

class KifiInstallationTest extends Specification {

  "KifiInstallation" should {
    "parse version strings and order correctly for extension" in {
      val v0 = KifiExtVersion("0.0.0")
      val v1 = KifiExtVersion("2.1.0")
      val v2 = KifiExtVersion("2.1.0")
      val v3 = KifiExtVersion("3.0.1")
      val v4 = KifiExtVersion("2.4.8")
      val v5 = KifiExtVersion("2.8.9990")
      val v6 = KifiExtVersion("2.9.22")

      v0 must be_<(v1)
      v1 must be_==(v1)
      v1 must be_==(v2)
      v1 must be_<(v3)
      v3 must be_>(v4)
      v4 must be_>(v2)
      v5 must be_<(v6)
    }
    "parse version strings and order correctly for iphone" in {
      val v0 = KifiIPhoneVersion("0.0.0")
      val v1 = KifiIPhoneVersion("2.1.0")
      val v2 = KifiIPhoneVersion("2.1.0")
      val v3 = KifiIPhoneVersion("3.0.1")
      val v4 = KifiIPhoneVersion("2.4.8")

      v0 must be_<(v1)
      v1 must be_==(v1)
      v1 must be_==(v2)
      v1 must be_<(v3)
      v3 must be_>(v4)
      v4 must be_>(v2)

      //KifiIPhoneVersion("2.4.8") > KifiExtVersion("1.4.8") YO! this does not compile
    }
    "fail to parse an invalid version string" in {
      KifiExtVersion("foo") must throwA[Exception]
    }

    "not mix platforms" in {
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
  }

}

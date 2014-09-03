package com.keepit.model

import org.specs2.mutable.Specification

import com.keepit.common.net.UserAgent
import com.keepit.common.db.{ ExternalId, Id }

class KifiInstallationTest extends Specification {

  "KifiInstallation" should {
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

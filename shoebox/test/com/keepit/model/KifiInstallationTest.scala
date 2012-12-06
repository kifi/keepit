package com.keepit.model

import org.specs2.mutable._

import com.keepit.common.db.CX._
import com.keepit.common.db._
import com.keepit.common.time.zones.PT
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test.Helpers._

class KifiInstallationTest extends SpecificationWithJUnit {

  "KifiInstallation" should {
    "parse version strings and order correctly" in {
      val v0 = KifiVersion("0.0.0")
      val v1 = KifiVersion("2.1.0")
      val v2 = KifiVersion("2.1.0")
      val v3 = KifiVersion("3.0.1")
      val v4 = KifiVersion("2.4.8")

      v0 must be_<  (v1)
      v1 must be_== (v1)
      v1 must be_== (v2)
      v1 must be_<  (v3)
      v3 must be_>  (v4)
      v4 must be_>  (v2)
    }
    "fail to parse an invalid version string" in {
      KifiVersion("foo") must throwA[Exception]
    }
    "persist" in {
      running(new EmptyApplication()) {
        val (user, install) = CX.withConnection { implicit conn =>
          val user = User(firstName = "Dafna", lastName = "Smith").save
          val install = KifiInstallation(userId = user.id.get, version = KifiVersion("1.1.1"), externalId = ExternalId[KifiInstallation](), userAgent = UserAgent.fromString("my browser")).save
          (user, install)
        }

        CX.withConnection { implicit conn =>
          KifiInstallation.get(install.id.get) === install
          val all = KifiInstallation.all(user.id.get)
          all.size === 1
          all.head === install
          KifiInstallation.get(user.id.get, install.externalId) === install
        }
      }
    }

  }

}

package com.keepit.commanders

import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.UserAgent
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._

import org.specs2.mutable.Specification

class KifiInstallationCommanderTest extends Specification with ShoeboxTestInjector {

  def modules = Nil

  "KifiInstallationCommanderTest" should {
    "iphone" in {
      withDb(modules: _*) { implicit injector =>
        val user1 = db.readWrite { implicit s =>
          val user1 = user().saved
          inject[KifiInstallationRepo].save(KifiInstallation(
            userId = user1.id.get,
            version = KifiIPhoneVersion("2.1.0"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my iphone"),
            platform = KifiInstallationPlatform.IPhone))
          user1
        }
        val commander = inject[KifiInstallationCommander]
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("2.2.4"), KifiIPhoneVersion("2.1.0")) === true
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("1.2.4"), KifiIPhoneVersion("2.1.0")) === true
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("1.2.4"), KifiIPhoneVersion("2.2.0")) === false
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("1.2.4"), KifiIPhoneVersion("1.2.0")) === true
      }
    }
    "android" in {
      withDb(modules: _*) { implicit injector =>
        val user1 = db.readWrite { implicit s =>
          val user1 = user().saved
          inject[KifiInstallationRepo].save(KifiInstallation(
            userId = user1.id.get,
            version = KifiAndroidVersion("2.1.0"),
            externalId = ExternalId[KifiInstallation](),
            userAgent = UserAgent("my android"),
            platform = KifiInstallationPlatform.Android))
          user1
        }
        val commander = inject[KifiInstallationCommander]
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("2.1.0"), KifiIPhoneVersion("3.1.0")) === true
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("2.1.1"), KifiIPhoneVersion("1.1.0")) === false
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("3.0.0"), KifiIPhoneVersion("2.2.0")) === false
        commander.isMobileVersionEqualOrGreaterThen(user1.id.get, KifiAndroidVersion("1.9.0"), KifiIPhoneVersion("1.2.0")) === true
      }
    }
  }
}

package com.keepit.model

import org.joda.time.DateTime
import org.junit.runner.RunWith
import org.specs2.mutable._
import org.specs2.runner.JUnitRunner

import com.keepit.common.db._
import com.keepit.common.db.CX._
import com.keepit.common.time.zones.PT
import com.keepit.test.EmptyApplication

import play.api.Play.current
import play.api.test._
import play.api.test.Helpers._

class ExtensionVersionTest extends SpecificationWithJUnit {

  "Bookmark" should {
    "persist" in {
      running(new EmptyApplication()) {
        val (user, version) = CX.withConnection { implicit conn =>
          val user = User(firstName = "Dafna", lastName = "Smith").save
          val version = ExtensionVersion(userId = user.id.get, version = "1.1.1", browserInstanceId = ExternalId[ExtensionVersion](), userAgent = UserAgent.fromString("my browser")).save
          (user, version)
        }

        CX.withConnection { implicit conn =>
          ExtensionVersion.get(version.id.get) === version
          val all = ExtensionVersion.all(user.id.get)
          all.size === 1
          all.head === version
          ExtensionVersion.getOpt(user.id.get, version.browserInstanceId).get === version
        }
      }
    }

  }

}

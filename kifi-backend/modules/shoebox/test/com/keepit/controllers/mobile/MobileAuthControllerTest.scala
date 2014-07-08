package com.keepit.controllers.mobile

import com.keepit.model._
import org.specs2.mutable.Specification


import com.keepit.common.controller._
import com.keepit.common.db.slick.Database
import com.keepit.inject.ApplicationInjector
import com.keepit.test.ShoeboxApplication

import play.api.libs.json.Json
import play.api.test._


import play.api.test.Helpers._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.scraper.{TestScraperServiceClientModule, FakeScrapeSchedulerModule}
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{FakeShoeboxSecureSocialModule, FakeSocialGraphModule}
import scala.util.Failure
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class MobileAuthControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeMailModule(),
    FakeShoeboxSecureSocialModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  "register iphone version" in {
    running(new ShoeboxApplication(controllerTestModules: _*)) {

      val userRepo = inject[UserRepo]
      val installationRepo = inject[KifiInstallationRepo]
      val db = inject[Database]

      val user = db.readWrite { implicit s =>
        userRepo.save(User(firstName = "Andrew", lastName = "C"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileAuthController.registerIPhoneVersion().toString
      path === "/m/1/iphone/version/register"

      inject[FakeActionAuthenticator].setUser(user)
      val existing = {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val installation = db.readWrite { implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        installation.platform === KifiInstallationPlatform.IPhone

        val expected = Json.parse( s"""
          {"installation":"${installation.externalId}","newInstallation":true}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        installation
      }
      {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3", "installation" -> existing.externalId.toString))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse( s"""
          {"installation":"${existing.externalId}","newInstallation":false}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        db.readOnly { implicit s =>
          installationRepo.count === 1
          installationRepo.all().head.version.toString === "1.2.3"
        }
      }
      {
        db.readOnly { implicit s =>
          installationRepo.get(existing.externalId).version.toString === "1.2.3"
          installationRepo.get(existing.externalId) === existing
        }
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.4", "installation" -> existing.externalId.toString))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse( s"""
          {"installation":"${existing.externalId}","newInstallation":false}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        db.readWrite { implicit s =>
          installationRepo.count === 1
          installationRepo.all().head.version.toString === "1.2.4"
          installationRepo.all().head.platform === KifiInstallationPlatform.IPhone
        }
      }
      {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val newOne = db.readWrite { implicit s =>
          val all = installationRepo.all()
          all.size === 2
          all(1)
        }
        val expected = Json.parse( s"""
          {"installation":"${newOne.externalId}","newInstallation":true}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }

    }
  }

  "register android version" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {

      val userRepo = inject[UserRepo]
      val installationRepo = inject[KifiInstallationRepo]
      val db = inject[Database]

      val user = db.readWrite {implicit s =>
        userRepo.save(User(firstName = "Andrew", lastName = "C"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileAuthController.registerAndroidVersion().toString
      path === "/m/1/android/version/register"

      inject[FakeActionAuthenticator].setUser(user)
      val existing = {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val installation = db.readWrite {implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        installation.platform === KifiInstallationPlatform.Android

        val expected = Json.parse(s"""
          {"installation":"${installation.externalId}","newInstallation":true}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        installation
      }
      {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3", "installation" -> existing.externalId.toString))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {"installation":"${existing.externalId}","newInstallation":false}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        db.readOnly {implicit s =>
          installationRepo.count === 1
          installationRepo.all().head.version.toString === "1.2.3"
        }
      }
      {
        db.readOnly {implicit s =>
          installationRepo.get(existing.externalId).version.toString === "1.2.3"
          installationRepo.get(existing.externalId) === existing
        }
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.4", "installation" -> existing.externalId.toString))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {"installation":"${existing.externalId}","newInstallation":false}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        db.readWrite {implicit s =>
          installationRepo.count === 1
          installationRepo.all().head.version.toString === "1.2.4"
          installationRepo.all().head.platform === KifiInstallationPlatform.Android
        }
      }
      {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val newOne = db.readWrite {implicit s =>
          val all = installationRepo.all()
          all.size === 2
          all(1)
        }
        val expected = Json.parse(s"""
          {"installation":"${newOne.externalId}","newInstallation":true}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }

    }

  }
}

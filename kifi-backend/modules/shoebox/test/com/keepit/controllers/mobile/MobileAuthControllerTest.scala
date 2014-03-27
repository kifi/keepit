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
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{FakeShoeboxSecureSocialModule, FakeSocialGraphModule}

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
    TestHeimdalServiceClientModule()
  )

  "register version" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {

      val userRepo = inject[UserRepo]
      val installationRepo = inject[KifiInstallationRepo]
      val db = inject[Database]

      val user = db.readWrite {implicit s =>
        userRepo.save(User(firstName = "Andrew", lastName = "C"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileAuthController.registerVersion().toString
      path === "/m/1/version/register"

      inject[FakeActionAuthenticator].setUser(user)
      val existing = {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val installation = db.readWrite {implicit s =>
          installationRepo.getOpt(user.id.get, KifiIPhoneVersion("1.2.3"), KifiInstallationPlatform.IPhone).get
        }

        val expected = Json.parse(s"""
          {"installation":"${installation.externalId}","newInstallation":true}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
        installation
      }
      {
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("version" -> "1.2.3"))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {"installation":"${existing.externalId}","newInstallation":false}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }

    }

  }
}

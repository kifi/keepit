package com.keepit.controllers.api

import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.test.FakeRequest
import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.model._
import play.api.test.Helpers._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScraperHealthMonitorModule }
import com.keepit.common.net.FakeHttpClientModule
import play.api.libs.json.JsString
import com.keepit.common.social.FakeShoeboxSecureSocialModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.search.TestSearchServiceClientModule
import play.api.libs.json.JsObject
import com.keepit.common.store.ShoeboxFakeStoreModule
import scala.reflect.internal.util.FakePos
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class DeskControllerTest extends Specification with ShoeboxApplicationInjector {

  def requiredModules = Seq(
    TestSearchServiceClientModule(),
    FakeScraperHealthMonitorModule(),
    FakeShoeboxSecureSocialModule(),
    ShoeboxFakeStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeMailModule(),
    TestHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  "DeskController" should {
    "is logged in" in {
      running(new ShoeboxApplication(requiredModules: _*)) {
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        }
        val path = com.keepit.controllers.api.routes.DeskController.isLoggedIn.toString
        path === "/api/desk/isLoggedIn"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        Json.parse(contentAsString(result)) must equalTo(Json.parse("""{"loggedIn":true,"firstName":"Shanee","lastName":"Smith"}"""))
      }
    }
  }
}

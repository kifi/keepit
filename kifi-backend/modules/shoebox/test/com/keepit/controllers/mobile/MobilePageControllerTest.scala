package com.keepit.controllers.mobile

import org.specs2.mutable.Specification

import com.keepit.common.controller._
import com.keepit.common.db.slick._
import com.keepit.common.db._
import com.keepit.common.social.BasicUserRepo
import com.keepit.model._
import com.keepit.search.{TestSearchServiceClientModule, Lang}
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}

import play.api.libs.json.{Json, JsNumber, JsArray}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.google.inject.Injector
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.social.{SocialNetworkType, SocialId, SocialNetworks}

class MobilePageControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    TestSliderHistoryTrackerModule()
  )

  "mobileController" should {
    "return connected users from the database" in {
      running(new ShoeboxApplication(mobileControllerTestModules:_*)) {
        val route = com.keepit.controllers.mobile.routes.MobilePageController.getPageDetails().toString
        route === "/m/1/page/details"

        val user = inject[Database].readWrite {implicit s =>
          userRepo.save(User(firstName="Richard",lastName="Feynman", externalId = ExternalId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a672")))
        }
        inject[FakeActionAuthenticator].setUser(user)
        val mobileController = inject[MobilePageController]
        //this is a POST result
        val jsonParam = Json.parse("""{"url": "http://www.foo.com"}""")
        (jsonParam \ "url").as[String]
        val request = FakeRequest()//FakeRequest("POST", route).withJsonBody(jsonParam)

        val result = await(mobileController.getPageDetails()(request).run)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.parse("""[
            {"id":"e58be33f-51ad-4c7d-a88e-d4e6e3c9a676","firstName":"Albert","lastName":"Einstein","pictureName":"0.jpg"}
          ]""")
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }
  }
}

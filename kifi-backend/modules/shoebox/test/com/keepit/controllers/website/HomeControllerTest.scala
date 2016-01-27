package com.keepit.controllers.website

import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.inject.FakeFortyTwoModule
import com.keepit.model.UserFactoryHelper._
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper

import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.{ UserEmailAddress, UserEmailAddressRepo, UserFactory, EmailVerificationCode }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class HomeControllerTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule(),
    FakeHttpClientModule(),
    FakeHealthcheckModule(),
    FakeABookServiceClientModule(),
    FakeMailModule()
  )

  "HomeController" should {
    "iPhoneAppStoreRedirectWithTracking" should {
      "render HTML that redirects the user to the iOS app" in {
        running(new ShoeboxApplication(modules: _*)) {
          withDb(modules: _*) { implicit injector =>

            val controller = inject[HomeController]
            val call = controller.iPhoneAppStoreRedirect()
            val result = call(FakeRequest("GET", "/foo/bar?x=y"))

            val body = contentAsString(result)
            body must contain("kifi:/foo/bar?x=y")
          }
        }
      }
    }
    "install for ios" in {
      running(new ShoeboxApplication(modules: _*)) {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit s =>
            UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          }
          inject[FakeUserActionsHelper].setUser(user)
          val ctrl = inject[HomeController]
          val call = com.keepit.controllers.website.routes.HomeController.install
          val result = ctrl.install()(FakeRequest(call).withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3"))
          header("Location", result) === Some("https://itunes.apple.com/us/app/kifi/id740232575")
          status(result) === SEE_OTHER
        }
      }
    }
    "install for android" in {
      running(new ShoeboxApplication(modules: _*)) {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit s =>
            UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          }
          inject[FakeUserActionsHelper].setUser(user)
          val ctrl = inject[HomeController]
          val call = com.keepit.controllers.website.routes.HomeController.install
          val result = ctrl.install()(FakeRequest(call).withHeaders("user-agent" -> "Mozilla/5.0 (Linux; U; Android 4.0.3; ko-kr; LG-L160L Build/IML74K) AppleWebkit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"))
          header("Location", result) === Some("https://play.google.com/store/apps/details?id=com.kifi&hl=en")
          status(result) === SEE_OTHER
        }
      }
    }
    "install for extension" in {
      running(new ShoeboxApplication(modules: _*)) {
        withDb(modules: _*) { implicit injector =>
          val user = db.readWrite { implicit s =>
            UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          }
          inject[FakeUserActionsHelper].setUser(user)
          val ctrl = inject[HomeController]
          val call = com.keepit.controllers.website.routes.HomeController.install
          val result = ctrl.install()(FakeRequest(call).withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11"))
          status(result) === OK
        }
      }
    }
  }

}

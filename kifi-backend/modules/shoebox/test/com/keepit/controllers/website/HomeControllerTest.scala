package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class HomeControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxStoreModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeHttpClientModule(),
    FakeHealthcheckModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeScrapeSchedulerModule(),
    FakeExternalServiceModule()
  )

  "HomeController" should {
    "iPhoneAppStoreRedirectWithTracking" should {
      "render HTML that redirects the user to the iOS app" in {
        withDb(modules: _*) { implicit injector =>

          val controller = inject[HomeController]
          val call = controller.iPhoneAppStoreRedirect()
          val result = call(FakeRequest("GET", "/invite?friend=03dfb0f0-24cb-11e4-8c21-0800200c9a66"))

          val body = contentAsString(result)
          body must contain("kifi:TEST_MODE/invite?friend=03dfb0f0-24cb-11e4-8c21-0800200c9a66")
        }
      }
    }
  }

}

package com.keepit.controllers.website

import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MarketingSiteRouterTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq()

  "MarketingSiteRouter" should {
    "landing page routing as bot" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest().withHeaders("user-agent" -> "googlebot")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.2"
      }
    }
    "landing page routing" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest().withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.1"
      }
    }
    "landing page routing v1" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=1").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.1"
      }
    }
    "landing page routing v2" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=2").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.2"
      }
    }
    "landing page routing v3" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=3").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.1"
      }
    }
  }

}

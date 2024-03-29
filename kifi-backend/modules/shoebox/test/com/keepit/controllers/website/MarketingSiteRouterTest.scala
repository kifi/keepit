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
        landing === "index.16"
      }
    }
    "landing page routing" in {
      running(new ShoeboxApplication(modules: _*)) {
        FakeRequest().withHeaders("X-Forwarded-For" -> "8.8.8.8")
        val request = FakeRequest().withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.16"
      }
    }
    "landing page routing v16" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=16").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.16"
      }
    }
    "landing page routing v15" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=15").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.15"
      }
    }
    "landing page routing v14" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=14").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.14"
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
        landing === "index.3"
      }
    }
    "landing page routing v4" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=4").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.4"
      }
    }
    "landing page routing v5" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=5").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.5"
      }
    }
    "landing page routing v6" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=6").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.6"
      }
    }
    "landing page routing v7" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=7").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.7"
      }
    }
    "landing page routing v9" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=9").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.9"
      }
    }

    "landing page routing v10" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=10").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.10"
      }
    }

    "landing page routing v11" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=11").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.11"
      }
    }

    "landing page routing v12" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=12").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.12"
      }
    }

    "landing page routing v13" in {
      running(new ShoeboxApplication(modules: _*)) {
        val request = FakeRequest("GET", "?v=13").withHeaders("user-agent" -> "Mozilla/5.0 (X11; CrOS armv7l 2913.260.0) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.99 Safari/537.11")
        val landing = MarketingSiteRouter.landing(request)
        landing === "index.13"
      }
    }

    "substitute meta properties" in {
      def substitute(property: String, newContent: String)(html: String) = {
        val (pattern, newValue) = MarketingSiteRouter.substituteMetaProperty(property, newContent)
        pattern.replaceAllIn(html, newValue)
      }
      substitute("og:title", "Hi there!")("""<meta property="og:title" content="Connecting People With Knowledge" />""") === """<meta property="og:title" content="Hi there!"/>"""
      substitute("og:title", "Hi there!")("""<meta property="og:title" content="Connecting People With Knowledge">""") === """<meta property="og:title" content="Hi there!"/>"""
      substitute("og:title", "Hi there!")("""<meta property="og:title" content="Connecting People With Knowledge" >""") === """<meta property="og:title" content="Hi there!"/>"""
      substitute("og:description", "Hi there!")("""<meta property="og:description" content="Connecting People With Knowledge" />""") === """<meta property="og:description" content="Hi there!"/>"""
      substitute("og:title", "Hi there!")("""<meta property="og:description" content="Connecting People With Knowledge" />""") === """<meta property="og:description" content="Connecting People With Knowledge" />"""
    }

    "substitute link tags" in {
      def substitute(rel: String, newRef: String)(html: String) = {
        val (pattern, newValue) = MarketingSiteRouter.substituteLink(rel, newRef)
        pattern.replaceAllIn(html, newValue)
      }
      substitute("canonical", "http://www.kifi.com")("""<link rel="canonical" href="http://www.lemonde.fr" />""") === """<link rel="canonical" href="http://www.kifi.com"/>"""
    }
  }

}

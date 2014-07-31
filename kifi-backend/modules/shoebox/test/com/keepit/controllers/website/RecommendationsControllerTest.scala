package com.keepit.controllers.website

import com.keepit.abook.TestABookServiceClientModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.crypto.TestCryptoModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ FakeMailModule, TestMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ TestShoeboxAppSecureSocialModule, FakeSocialGraphModule, FakeAuthenticator }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.inject.ApplicationInjector
import com.keepit.model.ScoreType
import com.keepit.model.ScoreType.ScoreType
import com.keepit.scraper.{ TestScrapeSchedulerConfigModule, TestScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.shoebox.{ ShoeboxSlickModule, FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Json
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest }

import scala.concurrent.ExecutionContext.Implicits.global

class RecommendationsControllerTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(
    ShoeboxSlickModule(),
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    TestSearchServiceClientModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    TestShoeboxAppSecureSocialModule(),
    TestABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule(),
    TestScrapeSchedulerConfigModule(),
    FakeCuratorServiceClientModule()
  )

  "RecommendationsController" should {

    "call adHocRecos" in {
      running(new ShoeboxApplication(modules: _*)) {

        val controller = inject[RecommendationsController]

        val route = com.keepit.controllers.website.routes.RecommendationsController.adHocRecos(1).url

        route === "/site/recos/adHoc?n=1"
      }
    }
  }
}

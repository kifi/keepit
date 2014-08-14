package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.common.db.Id
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.{ FakeCuratorServiceClientModule }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule }
import com.keepit.test.{ ShoeboxTestInjector }
import org.specs2.mutable.{ SpecificationLike }
import play.api.libs.json.{ Json }
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test.{ FakeRequest }

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RecommendationsControllerTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {

  val modules = Seq(
    FakeShoeboxServiceModule(),
    FakeCuratorServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeActorSystemModule()
  )

  "RecommendationsController" should {

    "call adHocRecos" in {
      withInjector(modules: _*) { implicit injector =>
        val route = com.keepit.controllers.website.routes.RecommendationsController.adHocRecos(1).url
        route === "/site/recos/adHoc?n=1"
      }
    }

    "update uri recommendation feedback" in {
      withInjector(modules: _*) { implicit injector =>
        val url = "id1"
        val route = com.keepit.controllers.website.routes.RecommendationsController.updateUriRecommendationFeedback().url
        route === "/site/recos/feedback"

        //        val payload = Json.arr(
        //          "url" -> "url1",
        //          "feedback" -> {
        //            "delivered" -> 1,
        //            "clicked" -> 1
        //          }
        //        )
        val input = Json.parse(
          s"""
             |{ "url": "https://www.google.com",
             |"feedback": {"delivered": 1,
             |             "clicked": 1} }
             |""".stripMargin)

        val request = FakeRequest("POST", route).withBody(input)

        val controller = inject[RecommendationsController]
        val result: Future[SimpleResult] = controller.updateUriRecommendationFeedback()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
      }
    }
  }
}

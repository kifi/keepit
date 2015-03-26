package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.db.{ Id, ExternalId }

import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ Username, User, NormalizedURI }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

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
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
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
      withDb(modules: _*) { implicit injector =>
        val url = "id1"
        val route = com.keepit.controllers.website.routes.RecommendationsController.
          updateUriRecommendationFeedback(ExternalId[NormalizedURI]("58328718-0222-47bf-9b12-d2d781cb8b0c")).url
        route === "/m/1/recos/feedback?id=58328718-0222-47bf-9b12-d2d781cb8b0c"

        val input = Json.parse(
          s"""{"clicked": true}""".stripMargin)

        inject[FakeUserActionsHelper].setUser(User(id = Some(Id[User](1L)), firstName = "Foo", lastName = "Bar", username = Username("test"), normalizedUsername = "test"))
        val request = FakeRequest("POST", route).withBody(input)

        val controller = inject[RecommendationsController]
        val result: Future[Result] = controller.
          updateUriRecommendationFeedback(ExternalId[NormalizedURI]("58328718-0222-47bf-9b12-d2d781cb8b0c"))(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

      }
    }
  }
}

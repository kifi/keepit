package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.db.ExternalId

import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ UserFactory, NormalizedURI }
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
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUserActionsModule(),
    FakeActorSystemModule()
  )

  "RecommendationsController" should {

    "update uri recommendation feedback" in {
      withDb(modules: _*) { implicit injector =>
        val url = "id1"
        val route = com.keepit.controllers.website.routes.RecommendationsController.
          updateUriRecommendationFeedback(ExternalId[NormalizedURI]("58328718-0222-47bf-9b12-d2d781cb8b0c")).url
        route === "/m/1/recos/feedback?id=58328718-0222-47bf-9b12-d2d781cb8b0c"

        val input = Json.parse(
          s"""{"clicked": true}""".stripMargin)

        inject[FakeUserActionsHelper].setUser(UserFactory.user().withId(1).withName("Foo", "Bar").withUsername("test").get)
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

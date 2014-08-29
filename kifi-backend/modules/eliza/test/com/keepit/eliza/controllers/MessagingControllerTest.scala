package com.keepit.eliza.controllers

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.realtime.FakeUrbanAirshipModule
import com.keepit.scraper.FakeScraperServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test.{ FakeHeaders, FakeRequest }

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class MessagingControllerTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with DbInjectionHelper {
  def modules = Seq(
    FakeSearchServiceClientModule(),
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUrbanAirshipModule(),
    FakeActionAuthenticatorModule(),
    FakeCryptoModule(),
    FakeScraperServiceClientModule(),
    FakeElizaStoreModule(),
    FakeHttpClientModule(),
    FakeActorSystemModule()
  )

  "MessagingController" should {
    "check batch threads" in {
      withDb(modules: _*) { implicit injector =>
        val messagingController = inject[MessagingController]
        val route = com.keepit.eliza.controllers.internal.routes.MessagingController.checkBatchThreads(Id[User](42)).url
        route === "/internal/eliza/checkBatchThreads?userId=42"
        val uris = Seq(Id[NormalizedURI](1), Id[NormalizedURI](2))
        val json = Json.toJson(uris)
        val input = Json.parse(s"""[{"uriId": "1"}]""".stripMargin)
        val request = FakeRequest("POST", route, FakeHeaders(Seq("Content-Type" -> Seq("application/json"))), json)
        val result: Future[SimpleResult] = messagingController.checkBatchThreads(Id[User](42))(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val jsonResponse: String = contentAsString(result)
        jsonResponse === "[]"
      }
    }
  }
}

package com.keepit.scraper

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.scraper.embedly.TestEmbedlyModule
import com.keepit.scraper.fetcher.TestHttpFetcherModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ScraperTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.http.Status
import play.api.libs.json.Json
import play.api.mvc.SimpleResult
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ScraperControllerTest extends TestKit(ActorSystem()) with SpecificationLike with ScraperTestInjector {

  val testFetcher: PartialFunction[String, HttpFetchStatus] = {
    case "https://www.google.com/" => HttpFetchStatus(Status.OK, None, new FetcherHttpContext {
      def destinationUrl: Option[String] = None
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }
  val testFetcherModule = TestHttpFetcherModule(Some(testFetcher))

  def modules = {
    Seq(
      testFetcherModule,
      TestEmbedlyModule(),
      TestScraperProcessorActorModule(),
      ScraperTestStoreModule(),
      FakeShoeboxServiceModule(),
      FakeActionAuthenticatorModule(),
      TestActorSystemModule(Some(system))
    )
  }

  "Scraper Controller" should {
    "retrieves article content" in {
      withInjector(modules: _*) { implicit injector =>
        val controller = inject[ScraperController]
        val path = com.keepit.scraper.routes.ScraperController.getBasicArticle.url
        path === "/internal/scraper/getBasicArticle"
        val input = Json.parse("""{ "url": "https://www.google.com" }""")
        val request = FakeRequest("POST", path).withBody(input)
        val result: Future[SimpleResult] = controller.getBasicArticle()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val json = contentAsJson(result)
        (json \ "destinationUrl").as[String] === "https://www.google.com"
      }
    }
  }
}


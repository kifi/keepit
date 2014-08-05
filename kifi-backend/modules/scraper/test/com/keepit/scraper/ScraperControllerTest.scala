package com.keepit.scraper

import com.keepit.common.actor.{ TestKitSupport, FakeActorSystemModule }
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.scraper.embedly.FakeEmbedlyModule
import com.keepit.scraper.fetcher.FakeHttpFetcherModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ScraperTestInjector
import play.api.http.Status
import play.api.libs.json.Json
import play.api.mvc.SimpleResult
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ScraperControllerTest extends TestKitSupport with ScraperTestInjector {

  val testFetcher: PartialFunction[String, HttpFetchStatus] = {
    case "https://www.google.com/" => HttpFetchStatus(Status.OK, None, new FetcherHttpContext {
      def destinationUrl: Option[String] = None
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }
  val testFetcherModule = FakeHttpFetcherModule(Some(testFetcher))

  def modules = {
    Seq(
      testFetcherModule,
      FakeEmbedlyModule(),
      FakeScraperProcessorActorModule(),
      ScraperTestStoreModule(),
      FakeShoeboxServiceModule(),
      FakeActionAuthenticatorModule(),
      FakeActorSystemModule()
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


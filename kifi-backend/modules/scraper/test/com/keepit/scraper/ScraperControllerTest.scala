package com.keepit.scraper

import akka.actor.ActorSystem
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import com.keepit.common.crypto.TestCryptoModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.scraper.actor.ScrapeProcessorActorImpl
import com.keepit.scraper.embedly.TestEmbedlyModule
import com.keepit.scraper.fetcher.TestHttpFetcherModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ScraperApplication, ScraperApplicationInjector }
import org.specs2.mutable.Specification
import play.api.http.Status
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class ScraperControllerTest extends Specification with ScraperApplicationInjector {

  val testFetcher: PartialFunction[String, HttpFetchStatus] = {
    case "https://www.google.com/" => HttpFetchStatus(Status.OK, None, new FetcherHttpContext {
      def destinationUrl: Option[String] = None
      def redirects: Seq[HttpRedirect] = Seq.empty
    })
  }
  val testFetcherModule = TestHttpFetcherModule(Some(testFetcher))

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      testFetcherModule,
      TestEmbedlyModule(),
      new TestScraperProcessorModule() {
        override def scrapeProcessor = inject[ScrapeProcessorActorImpl]
      },
      ScraperTestStoreModule(),
      FakeShoeboxServiceModule(),
      FakeActionAuthenticatorModule(),
      TestActorSystemModule()
    )
  }

  "Scraper Controller" should {
    "retrieves article content" in {
      running(new ScraperApplication(modules: _*)) {
        val path = com.keepit.scraper.routes.ScraperController.getBasicArticle.url
        path === "/internal/scraper/getBasicArticle"
        val controller = inject[ScraperController]
        val input = Json.parse("""{ "url": "https://www.google.com" }""")
        val request = FakeRequest("POST", path).withJsonBody(input)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val json = contentAsJson(result)
        (json \ "destinationUrl").as[String] === "https://www.google.com"
      }
    }
  }
}

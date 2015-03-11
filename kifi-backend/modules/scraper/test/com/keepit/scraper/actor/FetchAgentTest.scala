package com.keepit.scraper.actor

import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.concurrent.{ FakeExecutionContextModule, ExecutionContext }
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ScraperTestStoreModule
import com.keepit.common.time._
import com.keepit.rover.fetcher.FetchContext
import com.keepit.scraper._
import com.keepit.scraper.actor.InternalMessages.FetchJob
import com.keepit.scraper.actor.ScraperMessages.Fetch
import com.keepit.scraper.embedly.FakeEmbedlyModule
import com.keepit.scraper.fetcher.{ DeprecatedHttpFetchStatus, FakeDeprecatedHttpFetcherModule }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ScraperTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.http.Status

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

class FetchAgentTest extends TestKitSupport with SpecificationLike with ScraperTestInjector {

  implicit val fj = ExecutionContext.fj

  val testFetcher: PartialFunction[String, DeprecatedHttpFetchStatus] = {
    case "https://www.google.com/" => DeprecatedHttpFetchStatus(Status.OK, None, Some(FetchContext("https://www.google.com/", Seq.empty)))
  }
  val testFetcherModule = FakeDeprecatedHttpFetcherModule(Some(testFetcher))

  def modules = {
    Seq(
      testFetcherModule,
      FakeEmbedlyModule(),
      FakeExecutionContextModule(),
      FakeScraperProcessorActorModule(),
      ScraperTestStoreModule(),
      FakeShoeboxServiceModule(),
      FakeUserActionsModule(),
      FakeHttpClientModule(),
      FakeActorSystemModule()
    )
  }

  "Fetch Agent" should {
    "fetch stuff" in {
      withInjector(modules: _*) { implicit injector =>
        implicit val timeout = Timeout(15 seconds)
        val clock = inject[Clock]
        val actorRef = TestActorRef(inject[FetchAgent])
        val resF: Future[Option[BasicArticle]] = actorRef.ask(FetchJob(clock.now(), Fetch("https://www.google.com", None, None))).mapTo[Option[BasicArticle]]
        val result: Option[BasicArticle] = Await.result(resF, 5 seconds)
        result.isDefined === true
      }
    }
  }
}

package com.keepit.eliza.ws

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsModule, DummyRequestHeader }
import com.keepit.common.store.{ ElizaDevStoreModule, FakeElizaStoreModule }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeShoeboxServiceClientModule }
import com.keepit.test.{ ElizaApplicationInjector, ElizaApplication, ElizaTestInjector }
import org.specs2.mutable.Specification
import org.specs2.time.{ NoTimeConversions, NoDurationConversions }
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import play.api.libs.json.{ JsArray, Json }

import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise, Await }

class ElizaWebSocketTest extends Specification with ElizaApplicationInjector with NoTimeConversions {

  val modules = List(
    FakeElizaStoreModule(),
    FakeHeimdalServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule()
  )

  def socket(in: JsArray*)(implicit injector: Injector): Future[List[JsArray]] = {
    val injected = inject[SharedWsMessagingController]

    val feed = Enumerator.enumerate(in)
    val resultsPromise = Promise[List[JsArray]]()

    val out = Iteratee.getChunks[JsArray].map { outputs =>
      resultsPromise.success(outputs)
    }.recover {
      case thrown: Throwable =>
        resultsPromise.failure(thrown)
    }.map(_ => ())

    val fn = Await.result(injected.websocket(None, None).f(DummyRequestHeader(3, "/dummy/url")), 1 second).right.get

    fn(feed, out)

    resultsPromise.future
  }

  "SharedWsMessagingController" should {

    "respond to ping" in {

      running(new ElizaApplication(modules: _*)) {
        socket(Json.arr("ping")) must beEqualTo(List(Json.arr("pong"))).await
      }

    }

  }

}

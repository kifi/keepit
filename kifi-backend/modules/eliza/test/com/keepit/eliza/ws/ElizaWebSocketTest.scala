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
import org.specs2.matcher.{ MatchResult, Expectable, MustExpectable, Matcher }
import org.specs2.mutable.Specification
import org.specs2.time.{ NoTimeConversions, NoDurationConversions }
import play.api.libs.iteratee.{ Iteratee, Enumerator }
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest

import play.api.test.Helpers._
import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise, Await }

class ElizaWebSocketTest extends WebSocketTest with ElizaApplicationInjector with NoTimeConversions {

  val modules = List(
    FakeElizaStoreModule(),
    FakeHeimdalServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule()
  )

  "SharedWsMessagingController" should {

    "respond to ping" in {

      running(new ElizaApplication(modules: _*)) {

        List(Json.arr("ping")) must leadToSocketOutput(equalTo(List(Json.arr("pong"))))

      }

    }

  }

}

package com.keepit.controllers.ext


import com.keepit.test.{SearchApplication, SearchApplicationInjector}
import org.specs2.mutable._

import com.keepit.model.User
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.inject._
import com.keepit.common.time._
import com.keepit.common.controller.{FakeActionAuthenticator, FakeActionAuthenticatorModule}
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.google.inject.Injector

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem

class ExtUserSearchControllerTest extends Specification with SearchApplicationInjector {

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      StandaloneTestActorSystemModule(),
      FakeActionAuthenticatorModule()
    )
  }

  "ExtUserSearchController" should {
    "do a trivial search" in {
      running(new SearchApplication(modules:_*)) {
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))

        val path = com.keepit.controllers.ext.routes.ExtUserSearchController.search("elephents", None, None, 1).toString
        path === "/search/users/search?query=elephents&maxHits=1"

        val controller = inject[ExtUserSearchController]
        inject[FakeActionAuthenticator].setUser(shanee)
        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          {
            "hits":[],
            "context":"AgABAAEA"
          }
          """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }
  }
}

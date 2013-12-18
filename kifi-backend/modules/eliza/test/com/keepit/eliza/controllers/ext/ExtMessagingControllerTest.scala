package com.keepit.eliza.controllers.ext

import com.keepit.test.{ElizaApplication, ElizaApplicationInjector}
import org.specs2.mutable._

import com.keepit.common.db.slick._

import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.common.db.Id
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule}
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.db.Id
import com.keepit.model.User
import com.keepit.social.BasicUser
import com.keepit.realtime.{UrbanAirship, FakeUrbanAirship, FakeUrbanAirshipModule}
import com.keepit.heimdal.{HeimdalContext, TestHeimdalServiceClientModule}
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.abook.{FakeABookServiceClientImpl, ABookServiceClient, TestABookServiceClientModule}

import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.NotificationRouter
import com.keepit.eliza.commanders.{MessagingCommander, MessagingIndexCommander}
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.model._

import com.google.inject.Injector

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem

class ExtMessagingController extends Specification with ElizaApplicationInjector {

  implicit val context = HeimdalContext.empty

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      ElizaCacheModule(),
      FakeShoeboxServiceModule(),
      TestHeimdalServiceClientModule(),
      FakeElizaServiceClientModule(),
      StandaloneTestActorSystemModule(),
      TestABookServiceClientModule(),
      FakeUrbanAirshipModule()
    )
  }

  "ExtMessaging Controller" should {

    "send correctly" in {
      running(new ElizaApplication(modules:_*)) {
        val user = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith")
        val path = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageAction().toString
        path === "/eliza/messages"

        val controller = inject[ExtMessagingController]
        inject[FakeActionAuthenticator].setUser(user)
        val input = Json.parse("""{
            "url": "http://www.google.com"}
          """)
        val request = FakeRequest("POST", path)
            .withJsonBody(input)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
            {
              "id":"${user.externalId}",
              "firstName":"Shanee",
              "lastName":"Smith",
              "pictureName":"0.jpg",
              "emails":[],
              "experiments":["admin"]}
          """)

        Json.parse(contentAsString(result)) must equalTo(expected)

      }
    }
  }
}

package com.keepit.eliza.controllers.ext

import com.keepit.test.{ElizaApplication, ElizaApplicationInjector}
import org.specs2.mutable._
import com.keepit.common.db.slick._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.common.controller.{FakeActionAuthenticator, FakeActionAuthenticatorModule}
import com.keepit.inject._
import com.keepit.test.{DbTestInjector}
import com.keepit.shoebox.{ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl}
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.db.{Id, ExternalId}
import com.keepit.model.User
import com.keepit.social.BasicUser
import com.keepit.realtime.{UrbanAirship, FakeUrbanAirship, FakeUrbanAirshipModule}
import com.keepit.heimdal.{HeimdalContext, TestHeimdalServiceClientModule}
import com.keepit.common.healthcheck.FakeAirbrakeNotifier
import com.keepit.abook.{FakeABookServiceClientImpl, ABookServiceClient, TestABookServiceClientModule}
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.model._
import com.keepit.common.crypto.TestCryptoModule
import com.google.inject.Injector
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.{Json, JsObject}
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.common.store.ElizaDevStoreModule
import com.keepit.common.store.ElizaFakeStoreModule

class ExtMessagingControllerTest extends Specification with ElizaApplicationInjector {

  implicit val context = HeimdalContext.empty

  def modules = {
    implicit val system = ActorSystem("test")
    Seq(
      FakeSearchServiceClientModule(),
      ElizaCacheModule(),
      FakeShoeboxServiceModule(),
      TestHeimdalServiceClientModule(),
      FakeElizaServiceClientModule(),
      StandaloneTestActorSystemModule(),
      TestABookServiceClientModule(),
      FakeUrbanAirshipModule(),
      FakeActionAuthenticatorModule(),
      TestCryptoModule(),
      TestScraperServiceClientModule(),
      ElizaFakeStoreModule()
    )
  }

  "ExtMessaging Controller" should {

    "send correctly" in {
      running(new ElizaApplication(modules:_*)) {
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)
        val path = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageAction().toString
        path === "/eliza/messages"

        val controller = inject[ExtMessagingController]
        inject[FakeActionAuthenticator].setUser(shanee)
        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.id}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val request = FakeRequest("POST", path).withJsonBody(input)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val messages = inject[Database].readOnly { implicit s => inject[MessageRepo].all }
        messages.size === 1
        val message = messages.head
        println(s"message = $message")
        val threads = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all }
        threads.size === 1
        val thread = threads.head
        println(s"thread = $thread")

        val expected = Json.parse(s"""
          {
            "id": "${message.externalId.id}",
            "parentId": "${thread.externalId.id}",
            "createdAt": "${message.createdAt.toStandardTimeString}",
            "threadInfo":{
              "id": "${thread.externalId.id}",
              "participants":
              [
                {
                  "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85",
                  "firstName":"Shanee",
                  "lastName":"Smith",
                  "pictureName":"0.jpg"
                },
                {
                  "id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484",
                  "firstName":"Shachaf",
                  "lastName":"Smith",
                  "pictureName":"0.jpg"
                }
              ],
              "digest": "test me out",
              "lastAuthor": "a9f67559-30fa-4bcd-910f-4c2fc8bbde85",
              "messageCount":1,
              "messageTimes": {
                "${message.externalId.id}": "${message.createdAt.toStandardTimeString}"
              },
              "createdAt": "${thread.createdAt.toStandardTimeString}",
              "lastCommentedAt": "${message.createdAt.toStandardTimeString}",
              "lastMessageRead": "${message.createdAt.toStandardTimeString}",
              "nUrl": "https://admin.kifi.com/admin/searchExperiments",
              "url": "https://admin.kifi.com/admin/searchExperiments",
              "muted":false
            },
            "messages":[
              {
                "id": "${message.externalId.id}",
                "createdAt": "${message.createdAt.toStandardTimeString}",
                "text": "test me out",
                "url": "https://admin.kifi.com/admin/searchExperiments",
                "nUrl": "https://admin.kifi.com/admin/searchExperiments",
                "user":{
                  "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg"
                },
                "participants":
                [
                  {
                    "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85",
                    "firstName":"Shanee",
                    "lastName":"Smith",
                    "pictureName":"0.jpg"
                  },
                  {
                    "id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484",
                    "firstName":"Shachaf",
                    "lastName":"Smith",
                    "pictureName":"0.jpg"
                  }
                ]
              }]
          }
          """)
        Json.parse(contentAsString(result)) must equalTo(expected)

      }
    }

    "sendMessageReplyAction" in {
      running(new ElizaApplication(modules:_*)) {
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)

        val controller = inject[ExtMessagingController]
        inject[FakeActionAuthenticator].setUser(shanee)
        val createThreadJson = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.id}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        status(route(FakeRequest("POST", "/eliza/messages").withJsonBody(createThreadJson)).get) must equalTo(OK)

        val message = inject[Database].readOnly { implicit s => inject[MessageRepo].all } head
        val thread = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all } head

        val path = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageReplyAction(thread.externalId).toString
        path === s"/eliza/messages/${thread.externalId}"

        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "cool man!",
            "recipients":["${shachaf.externalId.id}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val request = FakeRequest("POST", path).withJsonBody(input)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val messages = inject[Database].readOnly { implicit s => inject[MessageRepo].all }
        messages.size === 2
        val replys = messages filter {m => m.id != message.id}
        replys.size === 1
        val reply = replys.head
        reply.messageText === "cool man!"
        val expected = Json.parse(s"""
          {
            "id":"${reply.externalId.id}",
            "parentId":"${reply.threadExtId.id}",
            "createdAt":"${reply.createdAt.toStandardTimeString}"
          }
          """)
        Json.parse(contentAsString(result)) must equalTo(expected)

      }
    }
  }
}

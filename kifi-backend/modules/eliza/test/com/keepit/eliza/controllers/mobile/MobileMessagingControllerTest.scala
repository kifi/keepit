package com.keepit.eliza.controllers.mobile

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

import com.keepit.common.db.{Id, ExternalId}

import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.eliza.model._

import com.google.inject.Injector

import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.{JsArray, Json, JsObject}

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import akka.actor.ActorSystem
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import scala.Some
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.realtime.FakeUrbanAirshipModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.actor.StandaloneTestActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.FakeActionAuthenticatorModule
import scala.Some
import com.keepit.abook.TestABookServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.realtime.FakeUrbanAirshipModule
import com.keepit.common.crypto.TestCryptoModule

class MobileMessagingControllerTest extends Specification with ElizaApplicationInjector {

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
      TestCryptoModule()
    )
  }

  "ExtMessaging Controller" should {

    "send correctly" in {
      running(new ElizaApplication(modules:_*)) {
        inject[Database].readOnly { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnly { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)
        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.sendMessageAction().toString
        path === "/m/1/eliza/messages"

        inject[MobileMessagingController]
        inject[FakeActionAuthenticator].setUser(shanee)
        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.toString}"],
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
        val threads = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all }
        threads.size === 1
        val thread = threads.head

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
                },{
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
                    {"id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg"},
                    {"id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484","firstName":"Shachaf","lastName":"Smith","pictureName":"0.jpg"}
                  ]
              }]
          }
          """)
        Json.parse(contentAsString(result)) must equalTo(expected)

      }
    }

    "getCompactThread" in {
      running(new ElizaApplication(modules:_*)) {
        inject[Database].readOnly { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnly { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        val fakeClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        fakeClient.saveUsers(shanee)
        fakeClient.saveUsers(shachaf)

        inject[FakeActionAuthenticator].setUser(shanee)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #3", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #4", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #5", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)

        val thread = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all.head }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.externalId.toString).toString
        path === s"/m/1/eliza/thread/${thread.externalId.toString}"

        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)

        contentType(result) must beSome("application/json")

        val messages = inject[Database].readOnly { implicit s => inject[MessageRepo].all }
        messages.size === 5

        val expected = Json.parse(s"""
          {
            "id": "${thread.externalId.id}",
            "uri": "https://admin.kifi.com/admin/searchExperiments",
            "nUrl": "https://admin.kifi.com/admin/searchExperiments",
            "participants": [
              {
                "id": "${shanee.externalId.id}",
                "firstName": "Shanee",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              },{
                "id": "${shachaf.externalId.id}",
                "firstName": "Shachaf",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              }
            ],
            "messages": [
              { "id": "${messages(0).externalId.id}", "time": ${messages(0).createdAt.getMillis}, "text": "message #1", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(1).externalId.id}", "time": ${messages(1).createdAt.getMillis}, "text": "message #2", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(2).externalId.id}", "time": ${messages(2).createdAt.getMillis}, "text": "message #3", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(3).externalId.id}", "time": ${messages(3).createdAt.getMillis}, "text": "message #4", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(4).externalId.id}", "time": ${messages(4).createdAt.getMillis}, "text": "message #5", "userId": "${shanee.externalId.id}" }
            ]
          }
        """)

        val res = Json.parse(contentAsString(result))
        val jsMessages = (res \ "messages").as[JsArray].value
        (messages map {m => m.externalId} mkString ",") === (jsMessages map {m => (m \ "id").as[String]} mkString ",")
        jsMessages.size === 5
        (jsMessages(0) \ "id").as[String] === messages(0).externalId.id
        (jsMessages(0) \ "time").as[Long] === messages(0).createdAt.getMillis
        (jsMessages(1) \ "id").as[String] === messages(1).externalId.id
        (jsMessages(1) \ "time").as[Long] === messages(1).createdAt.getMillis
        (jsMessages(2) \ "id").as[String] === messages(2).externalId.id
        (jsMessages(2) \ "time").as[Long] === messages(2).createdAt.getMillis
        (jsMessages(3) \ "id").as[String] === messages(3).externalId.id
        (jsMessages(3) \ "time").as[Long] === messages(3).createdAt.getMillis
        (jsMessages(4) \ "id").as[String] === messages(4).externalId.id
        (jsMessages(4) \ "time").as[Long] === messages(4).createdAt.getMillis
        res must equalTo(expected)
      }
    }

    "getPagedThread" in {
      running(new ElizaApplication(modules:_*)) {
        inject[Database].readOnly { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnly { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        val fakeClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        fakeClient.saveUsers(shanee)
        fakeClient.saveUsers(shachaf)

        inject[FakeActionAuthenticator].setUser(shanee)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #3", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #4", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #5", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """))).get) must equalTo(OK)

        val thread = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all.head }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.externalId.toString, 1000, None).toString
        path === s"/m/2/eliza/thread/${thread.externalId.toString}"   //?pageSize=1000&fromThreadId="

        val request = FakeRequest("GET", path)
        val result = route(request).get
        status(result) must equalTo(OK)

        contentType(result) must beSome("application/json")

        val messages = inject[Database].readOnly { implicit s => inject[MessageRepo].all }
        messages.size === 5

        val expected = Json.parse(s"""
          {
            "id": "${thread.externalId.id}",
            "uri": "https://admin.kifi.com/admin/searchExperiments",
            "nUrl": "https://admin.kifi.com/admin/searchExperiments",
            "participants": [
              {
                "id": "${shanee.externalId.id}",
                "firstName": "Shanee",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              },{
                "id": "${shachaf.externalId.id}",
                "firstName": "Shachaf",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              }
            ],
            "messages": [
              { "id": "${messages(4).externalId.id}", "time": ${messages(4).createdAt.getMillis}, "text": "message #5", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(3).externalId.id}", "time": ${messages(3).createdAt.getMillis}, "text": "message #4", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(2).externalId.id}", "time": ${messages(2).createdAt.getMillis}, "text": "message #3", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(1).externalId.id}", "time": ${messages(1).createdAt.getMillis}, "text": "message #2", "userId": "${shanee.externalId.id}" },
              { "id": "${messages(0).externalId.id}", "time": ${messages(0).createdAt.getMillis}, "text": "message #1", "userId": "${shanee.externalId.id}" }
            ]
          }
        """)

        val res = Json.parse(contentAsString(result))
        val jsMessages = (res \ "messages").as[JsArray].value
        (messages.reverse map {m => m.externalId} mkString ",") === (jsMessages map {m => (m \ "id").as[String]} mkString ",")
        jsMessages.size === 5
        (jsMessages(0) \ "id").as[String] === messages(4).externalId.id
        (jsMessages(0) \ "time").as[Long] === messages(4).createdAt.getMillis
        (jsMessages(1) \ "id").as[String] === messages(3).externalId.id
        (jsMessages(1) \ "time").as[Long] === messages(3).createdAt.getMillis
        (jsMessages(2) \ "id").as[String] === messages(2).externalId.id
        (jsMessages(2) \ "time").as[Long] === messages(2).createdAt.getMillis
        (jsMessages(3) \ "id").as[String] === messages(1).externalId.id
        (jsMessages(3) \ "time").as[Long] === messages(1).createdAt.getMillis
        (jsMessages(4) \ "id").as[String] === messages(0).externalId.id
        (jsMessages(4) \ "time").as[Long] === messages(0).createdAt.getMillis
        res must equalTo(expected)
      }
    }

    "sendMessageReplyAction" in {
      running(new ElizaApplication(modules:_*)) {
        inject[Database].readOnly { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnly { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"))
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"))
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)

        inject[MobileMessagingController]
        inject[FakeActionAuthenticator].setUser(shanee)
        val createThreadJson = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.toString}", "${shanee.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        status(route(FakeRequest("POST", "/m/1/eliza/messages").withJsonBody(createThreadJson)).get) must equalTo(OK)

        val message = inject[Database].readOnly { implicit s => inject[MessageRepo].all } head
        val thread = inject[Database].readOnly { implicit s => inject[MessageThreadRepo].all } head

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.sendMessageReplyAction(thread.externalId).toString
        path === s"/m/1/eliza/messages/${thread.externalId}"

        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "cool man!",
            "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val request = FakeRequest("POST", path).withJsonBody(input)
        val result = route(request).get
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        println(s"thread = $thread")

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

        //checking result with another call

        val pathThread = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.externalId.toString).toString
        pathThread === s"/m/1/eliza/thread/${thread.externalId.toString}"

        val request2 = FakeRequest("GET", pathThread)
        val result2 = route(request2).get
        status(result2) must equalTo(OK)

        contentType(result2) must beSome("application/json")

        val expected2 = Json.parse(s"""
          {
            "id": "${thread.externalId.id}",
            "uri": "https://admin.kifi.com/admin/searchExperiments",
            "nUrl": "https://admin.kifi.com/admin/searchExperiments",
            "participants": [
              {
                "id": "${shanee.externalId.id}",
                "firstName": "Shanee",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              },{
                "id": "${shachaf.externalId.id}",
                "firstName": "Shachaf",
                "lastName": "Smith",
                "pictureName": "0.jpg"
              }
            ],
            "messages": [
              {
                "id": "${messages(0).externalId.id}",
                "time": ${messages(0).createdAt.getMillis},
                "text": "test me out",
                "userId": "${shanee.externalId.id}"
              },
              {
                "id": "${messages(1).externalId.id}",
                "time": ${messages(1).createdAt.getMillis},
                "text": "cool man!",
                "userId": "${shanee.externalId.id}"
              }
            ]
          }
        """)

        Json.parse(contentAsString(result2)) must equalTo(expected2)

      }
    }

  }
}

package com.keepit.eliza.controllers.ext

import akka.testkit.TestKit
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.test.{ DbInjectionHelper, ElizaTestInjector }
import org.specs2.mutable._
import com.keepit.common.db.slick._
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.common.controller.{ FakeActionAuthenticator, FakeActionAuthenticatorModule }
import com.keepit.inject._
import com.keepit.shoebox.{ ShoeboxServiceClient, FakeShoeboxServiceModule, FakeShoeboxServiceClientImpl }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.time._
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.model.User
import com.keepit.realtime.{ FakeUrbanAirshipModule }
import com.keepit.heimdal.{ HeimdalContext, TestHeimdalServiceClientModule }
import com.keepit.abook.{ TestABookServiceClientModule }
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model._
import com.keepit.common.crypto.FakeCryptoModule
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json.Json
import akka.actor.ActorSystem
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.common.store.ElizaFakeStoreModule

class ExtMessagingControllerTest extends TestKit(ActorSystem()) with SpecificationLike with ElizaTestInjector with DbInjectionHelper {

  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeSearchServiceClientModule(),
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    TestHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    TestABookServiceClientModule(),
    FakeUrbanAirshipModule(),
    FakeActionAuthenticatorModule(),
    FakeCryptoModule(),
    TestScraperServiceClientModule(),
    ElizaFakeStoreModule(),
    FakeHttpClientModule(),
    TestActorSystemModule(Some(system))
  )

  "ExtMessaging Controller" should {

    "send correctly" in {
      withDb(modules: _*) { implicit injector =>
        val extMessagingController = inject[ExtMessagingController]
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
        val request = FakeRequest("POST", path).withBody(input)
        val result = extMessagingController.sendMessageAction()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val messages = db.readOnlyMaster { implicit s => inject[MessageRepo].all }
        messages.size === 1
        val message = messages.head
        println(s"message = $message")
        val threads = db.readOnlyMaster { implicit s => inject[MessageThreadRepo].all }
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
      withDb(modules: _*) { implicit injector =>
        val extMessagingController = inject[ExtMessagingController]
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
        val path1 = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageAction().toString
        val request1 = FakeRequest("POST", path1).withBody(createThreadJson)
        val result1 = extMessagingController.sendMessageAction()(request1)
        status(result1) must equalTo(OK)

        val message = db.readOnlyMaster { implicit s => inject[MessageRepo].all } head
        val thread = db.readOnlyMaster { implicit s => inject[MessageThreadRepo].all } head

        val path2 = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageReplyAction(thread.externalId).toString
        path2 === s"/eliza/messages/${thread.externalId}"

        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "cool man!",
            "recipients":["${shachaf.externalId.id}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val request2 = FakeRequest("POST", path2).withBody(input)
        val result2 = extMessagingController.sendMessageReplyAction(thread.externalId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val messages = db.readOnlyMaster { implicit s => inject[MessageRepo].all }
        messages.size === 2
        val replys = messages filter { m => m.id != message.id }
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
        Json.parse(contentAsString(result2)) must equalTo(expected)

      }
    }
  }
}

package com.keepit.eliza.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ UserFactory, User }
import com.keepit.realtime.{ FakeAppBoyModule, FakeUrbanAirshipModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.test.{ DbInjectionHelper, ElizaTestInjector }
import org.specs2.mutable._
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class ExtMessagingControllerTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with DbInjectionHelper {

  implicit val context = HeimdalContext.empty

  def initUsers()(implicit injector: Injector): Seq[User] = {
    val shanee = UserFactory.user().withId(42).withId("a9f67559-30fa-4bcd-910f-4c2fc8bbde85").withName("Shanee", "Smith").withUsername("test").get
    val shachaf = UserFactory.user().withId(43).withId("2be9e0e7-212e-4081-a2b0-bfcaf3e61484").withName("Shachaf", "Smith").withUsername("test").get
    val eishay = UserFactory.user().withId(44).withId("2be9e0e7-212e-4081-a2b0-bfcaf3e61483").withName("Eishay", "Smith").withUsername("test").get
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee, shachaf, eishay)
  }

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeSearchServiceClientModule(),
    ElizaCacheModule(),
    FakeShoeboxServiceModule(),
    FakeHeimdalServiceClientModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeUrbanAirshipModule(),
    FakeAppBoyModule(),
    FakeUserActionsModule(),
    FakeCryptoModule(),
    FakeElizaStoreModule(),
    FakeHttpClientModule(),
    FakeActorSystemModule(),
    FakeRoverServiceClientModule()
  )

  "ExtMessaging Controller" should {

    "send correctly" in {
      withDb(modules: _*) { implicit injector =>
        val extMessagingController = inject[ExtMessagingController]
        val Seq(shanee, shachaf, _) = initUsers()
        val path = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageAction().toString
        path === "/eliza/messages"

        val controller = inject[ExtMessagingController]
        inject[FakeUserActionsHelper].setUser(shanee)
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
        // println(s"message = $message") // can be removed?
        val threads = db.readOnlyMaster { implicit s => inject[MessageThreadRepo].all }
        threads.size === 1
        val thread = threads.head
        // println(s"thread = $thread") // can be removed?

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
                  "pictureName":"0.jpg",
                  "username": "test"
                },
                {
                  "id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484",
                  "firstName":"Shachaf",
                  "lastName":"Smith",
                  "pictureName":"0.jpg",
                  "username": "test"
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
                  "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username": "test"
                },
                "participants":
                [
                  {
                    "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85",
                    "firstName":"Shanee",
                    "lastName":"Smith",
                    "pictureName":"0.jpg",
                    "username": "test"
                  },
                  {
                    "id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484",
                    "firstName":"Shachaf",
                    "lastName":"Smith",
                    "pictureName":"0.jpg",
                    "username": "test"
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
        val Seq(shanee, shachaf, _) = initUsers()

        val controller = inject[ExtMessagingController]
        inject[FakeUserActionsHelper].setUser(shanee)
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

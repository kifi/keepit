package com.keepit.eliza.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.commanders.MessageWithBasicUser
import com.keepit.eliza.model._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, UserFactory, User }
import com.keepit.realtime.{ FakeAppBoyModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.test.{ DbInjectionHelper, ElizaTestInjector }
import org.joda.time.DateTime
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
        val messages = db.readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
        messages.size === 1
        val message = messages.head
        val threads = db.readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords }
        threads.size === 1
        val thread = threads.head
        val actual = contentAsJson(result)
        (actual \ "id").as[PublicId[Message]] === message.pubId
        (actual \ "parentId").as[PublicId[Keep]] === Keep.publicId(thread.keepId)
        val threadJson = actual \ "threadInfo"
        (threadJson \ "id").as[PublicId[Keep]] === Keep.publicId(thread.keepId)
        (threadJson \ "lastCommentedAt").as[DateTime] === message.createdAt
        (actual \ "messages").as[Seq[MessageWithBasicUser]].length === 1
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

        val message = db.readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords } head
        val thread = db.readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords } head

        val path2 = com.keepit.eliza.controllers.ext.routes.ExtMessagingController.sendMessageReplyAction(thread.pubKeepId).toString
        path2 === s"/eliza/messages/${thread.pubKeepId.id}"

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
        val result2 = extMessagingController.sendMessageReplyAction(thread.pubKeepId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val messages = db.readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
        messages.size === 2
        val replys = messages filter { m => m.id != message.id }
        replys.size === 1
        val reply = replys.head
        reply.messageText === "cool man!"
        val expected = Json.parse(s"""
          {
            "id":"${reply.pubId.id}",
            "parentId":"${reply.pubKeepId.id}",
            "createdAt":"${reply.createdAt.toStandardTimeString}"
          }
          """)
        inject[WatchableExecutionContext].drain()
        contentAsJson(result2) === expected
      }
    }

  }
}

package com.keepit.eliza.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration, FakeCryptoModule }
import com.keepit.common.db.slick._
import com.keepit.common.net.{ URISanitizer, FakeHttpClientModule }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.commanders.{ MessageWithBasicUser, ElizaThreadInfo }
import com.keepit.eliza.model._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, UserFactory, User }
import com.keepit.realtime.{ FakeAppBoyModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.social.BasicUser
import com.keepit.test.ElizaTestInjector
import org.joda.time.DateTime
import org.specs2.mutable._
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileMessagingControllerTest extends Specification with ElizaTestInjector {
  implicit val context = HeimdalContext.empty

  def initUsers()(implicit injector: Injector): Seq[User] = {
    val shanee = UserFactory.user().withId(42).withId("a9f67559-30fa-4bcd-910f-4c2fc8bbde85").withName("Shanee", "Smith").withUsername("test").get
    val shachaf = UserFactory.user().withId(43).withId("2be9e0e7-212e-4081-a2b0-bfcaf3e61484").withName("Shachaf", "Smith").withUsername("test").get
    val eishay = UserFactory.user().withId(44).withId("2be9e0e7-212e-4081-a2b0-bfcaf3e61483").withName("Eishay", "Smith").withUsername("test").get
    inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee, shachaf, eishay)
  }

  def modules = {
    Seq(
      FakeExecutionContextModule(),
      FakeSearchServiceClientModule(),
      ElizaCacheModule(),
      FakeShoeboxServiceModule(),
      FakeHeimdalServiceClientModule(),
      FakeElizaServiceClientModule(),
      FakeActorSystemModule(),
      FakeABookServiceClientModule(),
      FakeAppBoyModule(),
      FakeUserActionsModule(),
      FakeHttpClientModule(),
      FakeCryptoModule(),
      FakeElizaStoreModule(),
      FakeRoverServiceClientModule()
    )
  }

  "Mobile Messaging Controller" should {

    "getUnreadNotificationsCount" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].aTonOfRecords.isEmpty === true
          inject[NonUserThreadRepo].aTonOfRecords.isEmpty === true
        }

        val Seq(shanee, shachaf, _) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getUnreadNotificationsCount().toString
        path === s"/m/1/eliza/messages/unreadNotificationsCount"

        val result = inject[MobileMessagingController].getUnreadNotificationsCount()(FakeRequest().withHeaders("user-agent" -> "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))

        status(result) must equalTo(OK)
        contentType(result) must beSome("text/plain")
        contentAsString(result) === "0"
      }
    }

    "addParticipantsToThread" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].aTonOfRecords.isEmpty === true
          inject[NonUserThreadRepo].aTonOfRecords.isEmpty === true
        }
        val Seq(shanee, shachaf, eishay) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val controller = inject[MobileMessagingController]
        val sendMessageAction = controller.sendMessageAction()
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords.head }
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByKeep(thread.keepId).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get)
          inject[NonUserThreadRepo].getByKeepId(thread.keepId).map(_.participant.identifier).toSet === Set.empty
        }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.addParticipantsToThread(thread.pubKeepId, users = eishay.externalId.toString, emailContacts = "joe@smith.com,jack@smith.com").toString
        path === s"/m/1/eliza/thread/${thread.pubKeepId.id}/addParticipantsToThread?users=2be9e0e7-212e-4081-a2b0-bfcaf3e61483&emailContacts=joe%40smith.com%2Cjack%40smith.com"

        inject[FakeUserActionsHelper].setUser(shanee)

        val result = inject[MobileMessagingController].addParticipantsToThread(thread.pubKeepId, users = eishay.externalId.toString, emailContacts = "joe@smith.com,jack@smith.com")(FakeRequest().withHeaders("user-agent" -> "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))

        status(result) must equalTo(OK)
        contentType(result) must beSome("text/plain")
        val runners = inject[WatchableExecutionContext].drain()
        runners > 2

        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByKeep(thread.keepId).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get, eishay.id.get)
          inject[NonUserThreadRepo].getByKeepId(thread.keepId).map(_.participant.identifier).toSet === Set("joe@smith.com", "jack@smith.com")
        }
      }
    }

    "addParticipantsToThread with no email" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].aTonOfRecords.isEmpty === true
          inject[NonUserThreadRepo].aTonOfRecords.isEmpty === true
        }
        val Seq(shanee, shachaf, eishay) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val controller = inject[MobileMessagingController]
        val sendMessageAction = controller.sendMessageAction()
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords.head }
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByKeep(thread.keepId).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get)
          inject[NonUserThreadRepo].getByKeepId(thread.keepId).map(_.participant.identifier).toSet === Set.empty
        }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.addParticipantsToThread(thread.pubKeepId, users = eishay.externalId.toString, emailContacts = "").toString
        path === s"/m/1/eliza/thread/${thread.pubKeepId.id}/addParticipantsToThread?users=2be9e0e7-212e-4081-a2b0-bfcaf3e61483&emailContacts="

        inject[FakeUserActionsHelper].setUser(shanee)

        val result = inject[MobileMessagingController].addParticipantsToThread(thread.pubKeepId, users = eishay.externalId.toString, emailContacts = "")(FakeRequest().withHeaders("user-agent" -> "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))

        status(result) must equalTo(OK)
        contentType(result) must beSome("text/plain")
        val runners = inject[WatchableExecutionContext].drain()
        runners > 2

        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByKeep(thread.keepId).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get, eishay.id.get)
          inject[NonUserThreadRepo].getByKeepId(thread.keepId).map(_.participant.identifier).toSet === Set()
        }
      }
    }

    "send correctly" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val Seq(shanee, shachaf, _) = initUsers()
        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.sendMessageAction().toString
        path === "/m/1/eliza/messages"

        inject[FakeUserActionsHelper].setUser(shanee)
        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val result = inject[MobileMessagingController].sendMessageAction()(FakeRequest().withBody(input))

        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
        messages.size === 1
        val message = messages.head
        val threads = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords }
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

    "getCompactThread" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val Seq(shanee, shachaf, _) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val controller = inject[MobileMessagingController]
        val sendMessageAction = controller.sendMessageAction()
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #3", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #4", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #5", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords.head }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.pubKeepId).toString
        path === s"/m/1/eliza/thread/${thread.pubKeepId.id}"

        val result = controller.getCompactThread(thread.pubKeepId)(FakeRequest("GET", path))
        status(result) must equalTo(OK)

        contentType(result) must beSome("application/json")

        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
        messages.size === 5

        val res = Json.parse(contentAsString(result))

        (res \ "id").as[PublicId[Keep]] === thread.pubKeepId
        (res \ "uri").as[String] === "https://admin.kifi.com/admin/searchExperiments"
        (res \ "uri").as[String] === (res \ "nUrl").as[String]
        (res \ "participants").as[Seq[BasicUser]] === Seq(shanee, shachaf).map(BasicUser.fromUser)

        val jsMessages = (res \ "messages").as[JsArray].value
        (messages map (_.pubId)) === (jsMessages map { m => (m \ "id").as[PublicId[Message]] })
        jsMessages.size === 5
        (jsMessages(0) \ "id").as[PublicId[Message]] === messages(0).pubId
        (jsMessages(0) \ "time").as[Long] === messages(0).createdAt.getMillis
        (jsMessages(1) \ "id").as[PublicId[Message]] === messages(1).pubId
        (jsMessages(1) \ "time").as[Long] === messages(1).createdAt.getMillis
        (jsMessages(2) \ "id").as[PublicId[Message]] === messages(2).pubId
        (jsMessages(2) \ "time").as[Long] === messages(2).createdAt.getMillis
        (jsMessages(3) \ "id").as[PublicId[Message]] === messages(3).pubId
        (jsMessages(3) \ "time").as[Long] === messages(3).createdAt.getMillis
        (jsMessages(4) \ "id").as[PublicId[Message]] === messages(4).pubId
        (jsMessages(4) \ "time").as[Long] === messages(4).createdAt.getMillis
      }
    }

    "getPagedThread" in {
      withDb(modules: _*) { implicit injector =>
        val controller = inject[MobileMessagingController]
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val Seq(shanee, shachaf, _) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.sendMessageAction().toString()
        path === "/m/1/eliza/messages"
        status(controller.sendMessageAction()(FakeRequest().withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(controller.sendMessageAction()(FakeRequest().withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(controller.sendMessageAction()(FakeRequest().withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #3", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(controller.sendMessageAction()(FakeRequest().withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #4", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(controller.sendMessageAction()(FakeRequest().withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #5", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords.head }
        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
        messages.size === 5

        {
          val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.pubKeepId, 1000, None).toString
          path === s"/m/2/eliza/thread/${thread.pubKeepId.id}"

          val action = controller.getPagedThread(thread.pubKeepId, 1000, None)
          val result = action(FakeRequest("GET", path))
          status(result) must equalTo(OK)

          contentType(result) must beSome("application/json")

          val res = Json.parse(contentAsString(result))
          (res \ "id").as[PublicId[Keep]] === thread.pubKeepId
          (res \ "uri").as[String] === "https://admin.kifi.com/admin/searchExperiments"
          (res \ "uri").as[String] === (res \ "nUrl").as[String]
          (res \ "participants").as[Seq[BasicUser]] === Seq(shanee, shachaf).map(BasicUser.fromUser)

          val jsMessages = (res \ "messages").as[JsArray].value
          (messages.reverse map (_.pubId)) === (jsMessages map { m => (m \ "id").as[PublicId[Message]] })
          jsMessages.size === 5
          (jsMessages(0) \ "id").as[PublicId[Message]] === messages(4).pubId
          (jsMessages(0) \ "time").as[Long] === messages(4).createdAt.getMillis
          (jsMessages(1) \ "id").as[PublicId[Message]] === messages(3).pubId
          (jsMessages(1) \ "time").as[Long] === messages(3).createdAt.getMillis
          (jsMessages(2) \ "id").as[PublicId[Message]] === messages(2).pubId
          (jsMessages(2) \ "time").as[Long] === messages(2).createdAt.getMillis
          (jsMessages(3) \ "id").as[PublicId[Message]] === messages(1).pubId
          (jsMessages(3) \ "time").as[Long] === messages(1).createdAt.getMillis
          (jsMessages(4) \ "id").as[PublicId[Message]] === messages(0).pubId
          (jsMessages(4) \ "time").as[Long] === messages(0).createdAt.getMillis
        }
        {
          val path2 = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.pubKeepId, 3, None).toString
          path2 === s"/m/2/eliza/thread/${thread.pubKeepId.id}?pageSize=3"

          val action2 = controller.getPagedThread(thread.pubKeepId, 3, None)
          val res2 = Json.parse(contentAsString(action2(FakeRequest("GET", path2))))

          (res2 \ "id").as[PublicId[Keep]] === thread.pubKeepId
          (res2 \ "uri").as[String] === "https://admin.kifi.com/admin/searchExperiments"
          (res2 \ "uri").as[String] === (res2 \ "nUrl").as[String]
          (res2 \ "participants").as[Seq[BasicUser]] === Seq(shanee, shachaf).map(BasicUser.fromUser)

          (res2 \ "messages").as[JsArray].value.size === 3
          (messages.reverse.take(3) map (_.pubId)) === ((res2 \ "messages").as[JsArray].value map { m => (m \ "id").as[PublicId[Message]] })
        }
        {
          val path3 = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.pubKeepId, 3, Some(messages(2).pubId.id)).toString
          path3 === s"/m/2/eliza/thread/${thread.pubKeepId.id}?pageSize=3&fromMessageId=${messages(2).pubId.id}"

          val action3 = controller.getPagedThread(thread.pubKeepId, 3, Some(messages(2).pubId.id))
          val res3 = Json.parse(contentAsString(action3(FakeRequest())))

          (res3 \ "id").as[PublicId[Keep]] === thread.pubKeepId
          (res3 \ "uri").as[String] === "https://admin.kifi.com/admin/searchExperiments"
          (res3 \ "uri").as[String] === (res3 \ "nUrl").as[String]
          (res3 \ "participants").as[Seq[BasicUser]] === Seq(shanee, shachaf).map(BasicUser.fromUser)

          (res3 \ "messages").as[JsArray].value.size === 2
          (messages.reverse.drop(3) map (_.pubId)) === ((res3 \ "messages").as[JsArray].value map { m => (m \ "id").as[PublicId[Message]] })
        }
      }
    }

    "sendMessageReplyAction" in {
      withDb(modules: _*) { implicit injector =>
        val Seq(shanee, shachaf, _) = initUsers()

        inject[FakeUserActionsHelper].setUser(shanee)
        val createThreadJson = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "test me out",
            "recipients":["${shachaf.externalId.toString}", "${shanee.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)

        val controller = inject[MobileMessagingController]

        status(controller.sendMessageAction()(FakeRequest("POST", "/m/1/eliza/messages").withBody(createThreadJson))) must equalTo(OK)

        val message = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords } head
        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].aTonOfRecords } head

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.sendMessageReplyAction(thread.pubKeepId).toString
        path === s"/m/1/eliza/messages/${thread.pubKeepId.id}"

        val input = Json.parse(s"""
          {
            "title": "Search Experiments",
            "text": "cool man!",
            "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments",
            "extVersion": "2.6.65"
          }
          """)
        val action = controller.sendMessageReplyAction(thread.pubKeepId)
        val request = FakeRequest("POST", path).withBody(input)
        val result = action(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        // println(s"thread = $thread") // can be removed?

        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].aTonOfRecords }
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
        Json.parse(contentAsString(result)) must equalTo(expected)

        //checking result with another call

        val pathThread = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.pubKeepId).toString
        pathThread === s"/m/1/eliza/thread/${thread.pubKeepId.id}"

        val action2 = controller.getCompactThread(thread.pubKeepId)
        val request2 = FakeRequest("GET", pathThread)
        val result2 = action2(request2)
        status(result2) must equalTo(OK)

        contentType(result2) must beSome("application/json")

        val jsRes2 = contentAsJson(result2)

        (jsRes2 \ "id").as[PublicId[Keep]] === thread.pubKeepId
        (jsRes2 \ "uri").as[String] === "https://admin.kifi.com/admin/searchExperiments"
        (jsRes2 \ "uri").as[String] === (jsRes2 \ "nUrl").as[String]
        (jsRes2 \ "participants").as[Seq[BasicUser]] === Seq(shanee, shachaf).map(BasicUser.fromUser)
      }
    }

  }
}

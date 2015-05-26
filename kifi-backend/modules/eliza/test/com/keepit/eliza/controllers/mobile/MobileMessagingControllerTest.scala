package com.keepit.eliza.controllers.mobile

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.controller.{ FakeSecureSocialClientIdModule, FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.slick._
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time._
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model._
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Username, User }
import com.keepit.realtime.{ FakeAppBoyModule, FakeUrbanAirshipModule }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, FakeShoeboxServiceModule, ShoeboxServiceClient }
import com.keepit.test.ElizaTestInjector
import org.specs2.mutable._
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MobileMessagingControllerTest extends Specification with ElizaTestInjector {

  implicit val context = HeimdalContext.empty

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
      FakeUrbanAirshipModule(),
      FakeAppBoyModule(),
      FakeSecureSocialClientIdModule(),
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
          inject[UserThreadRepo].all.isEmpty === true
          inject[NonUserThreadRepo].all.isEmpty === true
        }
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test"), normalizedUsername = "test")
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)

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
          inject[UserThreadRepo].all.isEmpty === true
          inject[NonUserThreadRepo].all.isEmpty === true
        }
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test2"), normalizedUsername = "test2")
        val eishay = User(id = Some(Id[User](44)), firstName = "Eishay", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61483"), username = Username("test3"), normalizedUsername = "test3")
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(eishay)

        inject[FakeUserActionsHelper].setUser(shanee)
        val controller = inject[MobileMessagingController]
        val sendMessageAction = controller.sendMessageAction()
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all.head }
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByThread(thread.id.get).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get)
          inject[NonUserThreadRepo].getByMessageThreadId(thread.id.get).map(_.participant.identifier).toSet === Set.empty
        }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.addParticipantsToThread(threadId = thread.externalId, users = eishay.externalId.toString, emailContacts = "joe@smith.com,jack@smith.com").toString
        path === s"/m/1/eliza/thread/${thread.externalId}/addParticipantsToThread?users=2be9e0e7-212e-4081-a2b0-bfcaf3e61483&emailContacts=joe%40smith.com%2Cjack%40smith.com"

        inject[FakeUserActionsHelper].setUser(shanee)

        val result = inject[MobileMessagingController].addParticipantsToThread(threadId = thread.externalId, users = eishay.externalId.toString, emailContacts = "joe@smith.com,jack@smith.com")(FakeRequest().withHeaders("user-agent" -> "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))

        status(result) must equalTo(OK)
        contentType(result) must beSome("text/plain")
        val runners = inject[WatchableExecutionContext].drain()
        runners > 2

        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByThread(thread.id.get).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get, eishay.id.get)
          inject[NonUserThreadRepo].getByMessageThreadId(thread.id.get).map(_.participant.identifier).toSet === Set("joe@smith.com", "jack@smith.com")
        }
      }
    }

    "addParticipantsToThread with no email" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].all.isEmpty === true
          inject[NonUserThreadRepo].all.isEmpty === true
        }
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test2"), normalizedUsername = "test2")
        val eishay = User(id = Some(Id[User](44)), firstName = "Eishay", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61483"), username = Username("test3"), normalizedUsername = "test3")
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(eishay)

        inject[FakeUserActionsHelper].setUser(shanee)
        val controller = inject[MobileMessagingController]
        val sendMessageAction = controller.sendMessageAction()
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #1", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)
        status(sendMessageAction(FakeRequest("POST", "/m/1/eliza/messages").withBody(Json.parse(s"""{
            "title": "Search Experiments", "text": "message #2", "recipients":["${shachaf.externalId.toString}"],
            "url": "https://admin.kifi.com/admin/searchExperiments", "extVersion": "2.6.65" } """)))) must equalTo(OK)

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all.head }
        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByThread(thread.id.get).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get)
          inject[NonUserThreadRepo].getByMessageThreadId(thread.id.get).map(_.participant.identifier).toSet === Set.empty
        }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.addParticipantsToThread(threadId = thread.externalId, users = eishay.externalId.toString, emailContacts = "").toString
        path === s"/m/1/eliza/thread/${thread.externalId}/addParticipantsToThread?users=2be9e0e7-212e-4081-a2b0-bfcaf3e61483&emailContacts="

        inject[FakeUserActionsHelper].setUser(shanee)

        val result = inject[MobileMessagingController].addParticipantsToThread(threadId = thread.externalId, users = eishay.externalId.toString, emailContacts = "")(FakeRequest().withHeaders("user-agent" -> "iKeefee/1.0.12823 (Device-Type: iPhone, OS: iOS 7.0.6)"))

        status(result) must equalTo(OK)
        contentType(result) must beSome("text/plain")
        val runners = inject[WatchableExecutionContext].drain()
        runners > 2

        inject[Database].readOnlyMaster { implicit s =>
          inject[UserThreadRepo].getByThread(thread.id.get).map(_.user).toSet === Set(shanee.id.get, shachaf.id.get, eishay.id.get)
          inject[NonUserThreadRepo].getByMessageThreadId(thread.id.get).map(_.participant.identifier).toSet === Set()
        }
      }
    }

    "send correctly" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test"), normalizedUsername = "test")
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)
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
        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].all }
        messages.size === 1
        val message = messages.head
        val threads = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all }
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
                  "pictureName":"0.jpg","username":"test"
                },{
                  "id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484",
                  "firstName":"Shachaf",
                  "lastName":"Smith",
                  "pictureName":"0.jpg","username":"test"
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
                  "id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"
                },
                "participants":
                  [
                    {"id":"a9f67559-30fa-4bcd-910f-4c2fc8bbde85","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"},
                    {"id":"2be9e0e7-212e-4081-a2b0-bfcaf3e61484","firstName":"Shachaf","lastName":"Smith","pictureName":"0.jpg","username":"test"}
                  ]
              }]
          }
          """)
        Json.parse(contentAsString(result)) must equalTo(expected)

      }
    }

    "getCompactThread" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test"), normalizedUsername = "test")
        val fakeClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        fakeClient.saveUsers(shanee)
        fakeClient.saveUsers(shachaf)

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

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all.head }

        val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.externalId.toString).toString
        path === s"/m/1/eliza/thread/${thread.externalId.toString}"

        val result = controller.getCompactThread(thread.externalId.toString)(FakeRequest("GET", path))
        status(result) must equalTo(OK)

        contentType(result) must beSome("application/json")

        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].all }
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
                "pictureName": "0.jpg","username":"test"
              },{
                "id": "${shachaf.externalId.id}",
                "firstName": "Shachaf",
                "lastName": "Smith",
                "pictureName": "0.jpg","username":"test"
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
        (messages map { m => m.externalId } mkString ",") === (jsMessages map { m => (m \ "id").as[String] } mkString ",")
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
      withDb(modules: _*) { implicit injector =>
        val controller = inject[MobileMessagingController]
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test"), normalizedUsername = "test")
        val fakeClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
        fakeClient.saveUsers(shanee)
        fakeClient.saveUsers(shachaf)

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

        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all.head }
        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].all }
        messages.size === 5

        {
          val path = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.externalId.toString, 1000, None).toString
          path === s"/m/2/eliza/thread/${thread.externalId.toString}"

          val action = controller.getPagedThread(thread.externalId.toString, 1000, None)
          val result = action(FakeRequest("GET", path))
          status(result) must equalTo(OK)

          contentType(result) must beSome("application/json")

          val expectedMessages = s"""[
                { "id": "${messages(4).externalId.id}", "time": ${messages(4).createdAt.getMillis}, "text": "message #5", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(3).externalId.id}", "time": ${messages(3).createdAt.getMillis}, "text": "message #4", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(2).externalId.id}", "time": ${messages(2).createdAt.getMillis}, "text": "message #3", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(1).externalId.id}", "time": ${messages(1).createdAt.getMillis}, "text": "message #2", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(0).externalId.id}", "time": ${messages(0).createdAt.getMillis}, "text": "message #1", "userId": "${shanee.externalId.id}" }
              ]"""

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
                  "pictureName": "0.jpg","username":"test"
                },{
                  "id": "${shachaf.externalId.id}",
                  "firstName": "Shachaf",
                  "lastName": "Smith",
                  "pictureName": "0.jpg","username":"test"
                }
              ],
              "messages": $expectedMessages
            }
          """)

          val res = Json.parse(contentAsString(result))
          val jsMessages = (res \ "messages").as[JsArray].value
          (messages.reverse map { m => m.externalId } mkString ",") === (jsMessages map { m => (m \ "id").as[String] } mkString ",")
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
        {
          val path2 = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.externalId.toString, 3, None).toString
          path2 === s"/m/2/eliza/thread/${thread.externalId.toString}?pageSize=3"

          val action2 = controller.getPagedThread(thread.externalId.toString, 3, None)
          val res2 = Json.parse(contentAsString(action2(FakeRequest("GET", path2))))

          val expectedMessages2 = s"""[
                { "id": "${messages(4).externalId.id}", "time": ${messages(4).createdAt.getMillis}, "text": "message #5", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(3).externalId.id}", "time": ${messages(3).createdAt.getMillis}, "text": "message #4", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(2).externalId.id}", "time": ${messages(2).createdAt.getMillis}, "text": "message #3", "userId": "${shanee.externalId.id}" }
              ]"""

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
                  "pictureName": "0.jpg","username":"test"
                },{
                  "id": "${shachaf.externalId.id}",
                  "firstName": "Shachaf",
                  "lastName": "Smith",
                  "pictureName": "0.jpg","username":"test"
                }
              ],
              "messages": $expectedMessages2
            }
          """)

          (res2 \ "messages").as[JsArray].value.size === 3
          (messages.reverse.take(3) map { m => m.externalId } mkString ",") === ((res2 \ "messages").as[JsArray].value map { m => (m \ "id").as[String] } mkString ",")
          res2 must equalTo(expected2)
        }
        {
          val path3 = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getPagedThread(thread.externalId.toString, 3, Some(messages(2).externalId.toString)).toString
          path3 === s"/m/2/eliza/thread/${thread.externalId.toString}?pageSize=3&fromMessageId=${messages(2).externalId.toString}"

          val action3 = controller.getPagedThread(thread.externalId.toString, 3, Some(messages(2).externalId.toString))
          val res3 = Json.parse(contentAsString(action3(FakeRequest())))

          val expectedMessages3 = s"""[
                { "id": "${messages(1).externalId.id}", "time": ${messages(1).createdAt.getMillis}, "text": "message #2", "userId": "${shanee.externalId.id}" },
                { "id": "${messages(0).externalId.id}", "time": ${messages(0).createdAt.getMillis}, "text": "message #1", "userId": "${shanee.externalId.id}" }
              ]"""

          val expected3 = Json.parse(s"""
            {
              "id": "${thread.externalId.id}",
              "uri": "https://admin.kifi.com/admin/searchExperiments",
              "nUrl": "https://admin.kifi.com/admin/searchExperiments",
              "participants": [
                {
                  "id": "${shanee.externalId.id}",
                  "firstName": "Shanee",
                  "lastName": "Smith",
                  "pictureName": "0.jpg","username":"test"
                },{
                  "id": "${shachaf.externalId.id}",
                  "firstName": "Shachaf",
                  "lastName": "Smith",
                  "pictureName": "0.jpg","username":"test"
                }
              ],
              "messages": $expectedMessages3
            }
          """)

          (res3 \ "messages").as[JsArray].value.size === 2
          (messages.reverse.drop(3) map { m => m.externalId } mkString ",") === ((res3 \ "messages").as[JsArray].value map { m => (m \ "id").as[String] } mkString ",")
          res3 must equalTo(expected3)
        }
      }
    }

    "sendMessageReplyAction" in {
      withDb(modules: _*) { implicit injector =>
        inject[Database].readOnlyMaster { implicit s => inject[UserThreadRepo].count } === 0
        inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].count } === 0
        val shanee = User(id = Some(Id[User](42)), firstName = "Shanee", lastName = "Smith", externalId = ExternalId[User]("a9f67559-30fa-4bcd-910f-4c2fc8bbde85"), username = Username("test"), normalizedUsername = "test")
        val shachaf = User(id = Some(Id[User](43)), firstName = "Shachaf", lastName = "Smith", externalId = ExternalId[User]("2be9e0e7-212e-4081-a2b0-bfcaf3e61484"), username = Username("test"), normalizedUsername = "test")
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shanee)
        inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl].saveUsers(shachaf)

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

        val message = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].all } head
        val thread = inject[Database].readOnlyMaster { implicit s => inject[MessageThreadRepo].all } head

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
        val action = controller.sendMessageReplyAction(thread.externalId)
        val request = FakeRequest("POST", path).withBody(input)
        val result = action(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        // println(s"thread = $thread") // can be removed?

        val messages = inject[Database].readOnlyMaster { implicit s => inject[MessageRepo].all }
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
        Json.parse(contentAsString(result)) must equalTo(expected)

        //checking result with another call

        val pathThread = com.keepit.eliza.controllers.mobile.routes.MobileMessagingController.getCompactThread(thread.externalId.toString).toString
        pathThread === s"/m/1/eliza/thread/${thread.externalId.toString}"

        val action2 = controller.getCompactThread(thread.externalId.toString)
        val request2 = FakeRequest("GET", pathThread)
        val result2 = action2(request2)
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
                "pictureName": "0.jpg","username":"test"
              },{
                "id": "${shachaf.externalId.id}",
                "firstName": "Shachaf",
                "lastName": "Smith",
                "pictureName": "0.jpg","username":"test"
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

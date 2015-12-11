package com.keepit.eliza.controllers.site

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.{ FakeActorSystemModule, TestKitSupport }
import com.keepit.common.cache.ElizaCacheModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.discussion.Message
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.eliza.model._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ MessageFactory, MessageThreadFactory, _ }
import com.keepit.realtime.FakeAppBoyModule
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ DbInjectionHelper, ElizaInjectionHelpers, ElizaTestInjector }
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.{ Json, JsValue }
import play.api.mvc.{ AnyContentAsEmpty, Call }
import play.api.test.FakeRequest
import play.api.test.Helpers._

class WebsiteMessagingControllerTest extends TestKitSupport with SpecificationLike with ElizaTestInjector with DbInjectionHelper with ElizaInjectionHelpers {
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
  implicit def createFakeRequest(route: Call): FakeRequest[AnyContentAsEmpty.type] = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[WebsiteMessagingController]
  private def route = com.keepit.eliza.controllers.site.routes.WebsiteMessagingController

  "WebsiteMessagingController" should {
    "page through a keep's messages" in {
      withDb(modules: _*) { implicit injector =>
        val keepId = Id[Keep](1)
        val (user, thread, messages) = db.readWrite { implicit session =>
          val user = UserFactory.user().get
          val thread = MessageThreadFactory.thread().withKeep(keepId).saved
          val messages = MessageFactory.messages(20).map(_.withThread(thread).saved)
          (user, thread, messages)
        }
        val keepPubId = Keep.publicId(keepId)

        val pageSize = 3
        val expectedPages = messages.sortBy(m => (m.createdAt.getMillis, m.id.get.id)).reverse.map(_.id.get).grouped(pageSize).toList

        inject[FakeUserActionsHelper].setUser(user)
        val actualPages = Iterator.iterate(Seq.empty[PublicId[Message]]) { prevPage =>
          val fromIdOpt = prevPage.lastOption.map(_.id)
          val request = route.getMessagesOnKeep(keepPubId, pageSize, fromIdOpt)
          val response = controller.getMessagesOnKeep(keepPubId, pageSize, fromIdOpt)(request)
          val items = (contentAsJson(response) \ "messages").as[Seq[JsValue]]
          items.map { j => (j \ "id").as[PublicId[Message]] }
        }.toStream.tail.takeWhile(_.nonEmpty).toList

        actualPages.length === expectedPages.length
        (actualPages zip expectedPages) foreach {
          case (actual, expected) =>
            actual.map(pubId => ElizaMessage.fromMessageId(Message.decodePublicId(pubId).get)) === expected
        }
        1 === 1
      }
    }

    "allow users to delete their own keeps" in {
      withDb(modules: _*) { implicit injector =>
        val keepId = Id[Keep](1)
        val keepPubId = Keep.publicId(keepId)
        val (user1, user2, thread, messages, messageToDelete) = db.readWrite { implicit session =>
          val user1 = UserFactory.user().get
          val user2 = UserFactory.user().get
          val thread = MessageThreadFactory.thread().withKeep(keepId).saved
          val user1Messages = MessageFactory.messages(5).map(_.withThread(thread).from(MessageSender.User(user1.id.get)).saved)
          val user2Messages = MessageFactory.messages(5).map(_.withThread(thread).from(MessageSender.User(user2.id.get)).saved)
          (user1, user2, thread, user1Messages ++ user2Messages, user1Messages.last)
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val getRequest1 = route.getMessagesOnKeep(keepPubId)
        val getResponse1 = controller.getMessagesOnKeep(keepPubId, 20, None)(getRequest1)
        val messagesBefore = (contentAsJson(getResponse1) \ "messages").as[Seq[Message]]
        messagesBefore.length === 10
        messagesBefore.exists { msg => Message.decodePublicId(msg.pubId).get == ElizaMessage.toMessageId(messageToDelete.id.get) } must beTrue

        val payload = Json.obj("messageId" -> Message.publicId(ElizaMessage.toMessageId(messageToDelete.id.get)))
        val delRequest = route.deleteMessageOnKeep(keepPubId).withBody(payload)
        val delResponse1 = controller.deleteMessageOnKeep(keepPubId)(delRequest)
        status(delResponse1) must beEqualTo(OK)

        val getRequest2 = route.getMessagesOnKeep(keepPubId)
        val getResponse2 = controller.getMessagesOnKeep(keepPubId, 20, None)(getRequest2)
        val messagesAfter = (contentAsJson(getResponse2) \ "messages").as[Seq[Message]]
        messagesAfter.length === 9
        messagesAfter.exists { msg => Message.decodePublicId(msg.pubId).get == ElizaMessage.toMessageId(messageToDelete.id.get) } must beFalse
      }
    }

    "disallow users to delete others' keeps" in {
      withDb(modules: _*) { implicit injector =>
        val keepId = Id[Keep](1)
        val keepPubId = Keep.publicId(keepId)
        val (user1, user2, thread, messages, messageToDelete) = db.readWrite { implicit session =>
          val user1 = UserFactory.user().get
          val user2 = UserFactory.user().get
          val thread = MessageThreadFactory.thread().withKeep(keepId).saved
          val user1Messages = MessageFactory.messages(5).map(_.withThread(thread).from(MessageSender.User(user1.id.get)).saved)
          val user2Messages = MessageFactory.messages(5).map(_.withThread(thread).from(MessageSender.User(user2.id.get)).saved)
          (user1, user2, thread, user1Messages ++ user2Messages, user2Messages.last)
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val getRequest1 = route.getMessagesOnKeep(keepPubId)
        val getResponse1 = controller.getMessagesOnKeep(keepPubId, 20, None)(getRequest1)
        val messagesBefore = (contentAsJson(getResponse1) \ "messages").as[Seq[Message]]
        messagesBefore.length === 10
        messagesBefore.exists { msg => Message.decodePublicId(msg.pubId).get == ElizaMessage.toMessageId(messageToDelete.id.get) } must beTrue

        val payload = Json.obj("messageId" -> Message.publicId(ElizaMessage.toMessageId(messageToDelete.id.get)))
        val delRequest = route.deleteMessageOnKeep(keepPubId).withBody(payload)
        val delResponse1 = controller.deleteMessageOnKeep(keepPubId)(delRequest)
        status(delResponse1) must beEqualTo(FORBIDDEN)

        val getRequest2 = route.getMessagesOnKeep(keepPubId)
        val getResponse2 = controller.getMessagesOnKeep(keepPubId, 20, None)(getRequest2)
        val messagesAfter = (contentAsJson(getResponse2) \ "messages").as[Seq[Message]]
        messagesAfter.length === 10
        messagesAfter.exists { msg => Message.decodePublicId(msg.pubId).get == ElizaMessage.toMessageId(messageToDelete.id.get) } must beTrue
      }
    }
  }
}

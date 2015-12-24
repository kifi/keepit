package com.keepit.eliza.ws

import java.util.UUID

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.model._
import com.keepit.eliza.notify.WsTestBehavior
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model.{ Keep, MessageThreadFactory, User, SocialUserInfo }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import play.api.libs.json.{ JsNull, JsArray, Json }
import play.api.mvc.WebSocket
import play.api.test.Helpers._
import com.keepit.common.time._

class ElizaWebSocketTest extends Specification with ElizaApplicationInjector with NoTimeConversions with WsTestBehavior {

  val modules = List(
    FakeElizaStoreModule(),
    FakeHeimdalServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule(),
    FakeSecureSocialAuthenticatorPluginModule(),
    FakeSecureSocialUserPluginModule()
  )

  implicit def publicIdConfig(implicit injector: Injector): PublicIdConfiguration = inject[PublicIdConfiguration]
  implicit def ws: WebSocket[JsArray, JsArray] = inject[SharedWsMessagingController].websocket(None, None)

  "SharedWsMessagingController" should {

    "connect and disconnect" in {
      running(new ElizaApplication(modules: _*)) {

        setupUserIdentity
        val socket = MockWebSocket()

        socket.out === Json.arr("hi")
        socket.close
        socket.out === Json.arr("bye", "session")
      }
    }

    "respond to queries" in {
      running(new ElizaApplication(modules: _*)) {

        setupUserIdentity

        val socket = MockWebSocket()
        socket.out

        socket.in {
          Json.arr("ping")
        }

        socket.out === Json.arr("pong")

        socket.in {
          Json.arr("stats")
        }

        socket.out(0).as[String] must startWith("id:")

        val messageThreadRepo = inject[MessageThreadRepo]
        val uuid = UUID.randomUUID().toString

        db.readWrite { implicit s => MessageThreadFactory.thread().saved }

        socket.in {
          Json.arr("get_thread", uuid)
        }

        val thread2 = db.readWrite { implicit session => MessageThreadFactory.thread().withUsers(Id(1)).saved }
        val uuid2 = thread2.externalId.id

        socket.in {
          Json.arr("get_thread", uuid2)
        }

        val threadResponse = socket.out
        threadResponse(0).as[String] === "thread"
        val thread = threadResponse(1)
        (thread \ "id").as[String] === uuid2
        (thread \ "messages") === JsArray()

        socket.close
        socket.out === Json.arr("bye", "session")
      }
    }

    "notify of new messages" in {
      running(new ElizaApplication(modules: _*)) {

        setupUserIdentity
        val messagingCommander = inject[MessagingCommander]
        val socket = MockWebSocket()
        socket.out

        val messageThreadRepo = inject[MessageThreadRepo]
        val userThreadRepo = inject[UserThreadRepo]

        val user = Id[User](1)
        val (messageThread, userThread) = db.readWrite { implicit session =>
          val messageThread = MessageThreadFactory.thread().withUri(Id(1)).withOnlyStarter(user).withUsers(Id(2)).saved
          val userThread = userThreadRepo.save(UserThread.forMessageThread(messageThread)(user))
          (messageThread, userThread)
        }
        val pubId = Keep.publicId(messageThread.keepId).id

        implicit val context = new HeimdalContext(Map())

        messagingCommander.sendMessage(Id[User](2), messageThread.threadId, messageThread, "So long and thanks for all the fish", None, None)

        val message = socket.out
        message(0).as[String] === "message"
        message(1).as[String] === pubId
        val messageContent = message(2)
        (messageContent \ "text").as[String] === "So long and thanks for all the fish"
        (messageContent \ "participants").asInstanceOf[JsArray].value.length === 2
        socket.out(0).as[String] === "notification"
        socket.out === Json.arr("unread_notifications_count", 1, 1, 0)
        socket.close
        socket.out === Json.arr("bye", "session")
      }
    }

  }

}

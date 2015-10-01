package com.keepit.eliza.ws

import java.util.UUID

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.model._
import com.keepit.eliza.notify.WsTestBehavior
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model.{ User, SocialUserInfo }
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

        db.readWrite { implicit session =>
          messageThreadRepo.save(MessageThread(
            externalId = ExternalId(uuid),
            uriId = None,
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = None,
            participantsHash = None,
            replyable = true))
        }

        socket.in {
          Json.arr("get_thread", uuid)
        }

        val uuid2 = UUID.randomUUID().toString

        db.readWrite { implicit session =>
          messageThreadRepo.save(MessageThread(
            externalId = ExternalId(uuid2),
            uriId = None,
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = Some(MessageThreadParticipants(Set(Id(1)))),
            participantsHash = None,
            replyable = true))
        }

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
        val uuid = UUID.randomUUID().toString

        val (messageThread, userThread) = db.readWrite { implicit session =>

          val messageThread = messageThreadRepo.save(MessageThread(
            externalId = ExternalId(uuid),
            uriId = Some(Id(1)),
            url = None,
            nUrl = None,
            pageTitle = None,
            participants = Some(MessageThreadParticipants(Set(Id(1), Id(2)))),
            participantsHash = None,
            replyable = true))

          val userThread = userThreadRepo.save(UserThread(
            user = Id(1),
            threadId = messageThread.id.get,
            uriId = Some(Id(1)),
            lastSeen = None,
            lastMsgFromOther = None,
            lastNotification = JsNull,
            lastActive = Some(currentDateTime),
            started = true
          ))

          (messageThread, userThread)
        }

        implicit val context = new HeimdalContext(Map())

        messagingCommander.sendMessage(Id[User](2), messageThread.id.get, "So long and thanks for all the fish", None, None)

        val message = socket.out
        message(0).as[String] === "message"
        message(1).as[String] === uuid
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

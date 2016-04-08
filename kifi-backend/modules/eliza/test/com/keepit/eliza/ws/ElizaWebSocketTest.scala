package com.keepit.eliza.ws

import java.util.UUID

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.{ PublicId }
import com.keepit.common.db.{ Id }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.model._
import com.keepit.eliza.notify.WsTestBehavior
import com.keepit.heimdal.{ HeimdalContext, FakeHeimdalServiceClientModule }
import com.keepit.model.{ Keep, MessageThreadFactory, User }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import play.api.libs.json.{ JsArray, Json }
import play.api.mvc.WebSocket
import play.api.test.Helpers._

class ElizaWebSocketTest extends Specification with ElizaApplicationInjector with NoTimeConversions with WsTestBehavior {

  val modules = List(
    FakeElizaStoreModule(),
    FakeHeimdalServiceClientModule(),
    FakeRoverServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeActorSystemModule(),
    FakeUserActionsModule()
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

        db.readWrite { implicit s => MessageThreadFactory.thread().saved }

        socket.in {
          Json.arr("get_thread", UUID.randomUUID().toString)
        }

        val thread2 = db.readWrite { implicit session => MessageThreadFactory.thread().withUsers(Id(1)).saved }

        socket.in {
          Json.arr("get_thread", thread2.pubKeepId)
        }

        val threadResponse = socket.out
        threadResponse(0).as[String] === "thread"
        val thread = threadResponse(1)
        (thread \ "id").as[PublicId[Keep]] === thread2.pubKeepId
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

        implicit val context = new HeimdalContext(Map())

        messagingCommander.sendMessage(Id[User](2), messageThread, "So long and thanks for all the fish", None, None)

        val message = socket.out
        message(0).as[String] === "message"
        message(1).as[String] === messageThread.pubKeepId.id
        val messageContent = message(2)
        (messageContent \ "text").as[String] === "So long and thanks for all the fish"
        (messageContent \ "participants").asInstanceOf[JsArray].value.length === 2
        Set("notification", "event", "unread_notifications_count").contains(socket.out(0).as[String]) === true
        Set("notification", "event", "unread_notifications_count").contains(socket.out(0).as[String]) === true
        socket.close
        socket.out === Json.arr("bye", "session")
      }
    }

  }

}

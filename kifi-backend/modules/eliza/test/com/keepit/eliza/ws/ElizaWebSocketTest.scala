package com.keepit.eliza.ws

import java.util.UUID

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.common.time.{ CrossServiceTime, DEFAULT_DATE_TIME_ZONE, currentDateTime }
import com.keepit.discussion.MessageSource
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.model._
import com.keepit.eliza.notify.WsTestBehavior
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.{ Keep, MessageThreadFactory, User }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import play.api.libs.json.{ JsArray, Json }
import play.api.mvc.WebSocket
import play.api.test.Helpers._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ElizaWebSocketTest extends Specification with ElizaApplicationInjector with NoTimeConversions with WsTestBehavior {
  implicit def time: CrossServiceTime = CrossServiceTime(currentDateTime)
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
        val socket = MockWebSocket()
        socket.out

        val user1 = Id[User](1)
        val user2 = Id[User](2)

        implicit val context = new HeimdalContext(Map())
        val (messageThread, msg) = Await.result(messagingCommander.sendNewMessage(
          user1,
          Seq(user1, user2),
          Seq.empty,
          url = "http://www.lemonde.fr",
          titleOpt = None,
          messageText = "I need this to work",
          source = Some(MessageSource.CHROME)
        ), Duration.Inf)
        inject[WatchableExecutionContext].drain()

        val message = socket.out
        message(0).as[String] === "message"
        message(1).as[String] === messageThread.pubKeepId.id
        val messageContent = message(2)
        (messageContent \ "text").as[String] === msg.messageText
        (messageContent \ "participants").asInstanceOf[JsArray].value.length === 2
        val first = socket.out(0).as[String]
        val second = socket.out(0).as[String]
        val third = socket.out(0).as[String]
        first must beOneOf("event", "notification") // sent at the same time, so accept either for the first two events
        second must beOneOf("event", "notification")
        third === "unread_notifications_count"
        socket.close
        socket.out === Json.arr("bye", "session")
      }
    }

  }

}

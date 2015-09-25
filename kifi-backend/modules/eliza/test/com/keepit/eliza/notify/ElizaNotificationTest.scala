package com.keepit.eliza.notify

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.eliza.ws.MockWebSocket
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ User, UserExperiment, UserExperimentType, SocialUserInfo }
import com.keepit.notify.NotificationProcessing
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.DepressedRobotGrumble
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import play.api.libs.json.{ Json, JsArray }
import com.keepit.common.time._
import play.api.mvc.WebSocket
import play.api.test.Helpers._

class ElizaNotificationTest extends Specification with ElizaApplicationInjector with NoTimeConversions with WsTestBehavior {

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

  "Eliza notifications" should {

    "Notify users of new notifications" in {

      running(new ElizaApplication(modules: _*)) {
        setupSocialUser
        setupUserExperiment(true)
        val socket = MockWebSocket()

        socket.out

        val processing = inject[NotificationProcessing]

        processing.processNewEvent(DepressedRobotGrumble(
          Recipient(Id[User](1)),
          currentDateTime,
          robotName = "Marvin",
          grumblingAbout = "Life, the universe, and everything"
        ))

        val notifOut = socket.out
        notifOut(0).as[String] === "notification"
        val notif = notifOut(1)
        (notif \ "bodyHtml").as[String] === "Marvin just grumbled about Life, the universe, and everything"
        (notif \ "category").as[String] === "global"
        (notif \ "unread").as[Boolean] === true
      }

    }

    "Retrieve notifications properly" in {

      running(new ElizaApplication(modules: _*)) {
        setupSocialUser
        setupUserExperiment(true)
        val socket = MockWebSocket()

        socket.out

        val processing = inject[NotificationProcessing]

        processing.processNewEvent(DepressedRobotGrumble(
          Recipient(Id[User](1)),
          currentDateTime,
          robotName = "Marvin",
          grumblingAbout = "Life, the universe, and everything"
        ))
        socket.out

        socket.in(Json.arr("get_latest_threads", 1, 100))

        val latest_threads = socket.out
        latest_threads(1).as[JsArray].value.length === 1
        val notif = latest_threads(1)(0)
        (notif \ "title").as[String] === "A robot just grumbled! He must be depressed..."
        (notif \ "unread").as[Boolean] === true
        (notif \ "unreadMessages").as[Int] === 1
      }

    }

    "Handle read/unread notifications" in {

      running(new ElizaApplication(modules: _*)) {
        setupSocialUser
        setupUserExperiment(true)
        val socket = MockWebSocket()

        socket.out

        val processing = inject[NotificationProcessing]

        processing.processNewEvent(DepressedRobotGrumble(
          Recipient(Id[User](1)),
          currentDateTime,
          robotName = "Marvin",
          grumblingAbout = "Life, the universe, and everything"
        ))
        socket.out

        socket.in(Json.arr("get_unread_threads", 1, 100))

        val unread_threads = socket.out
        unread_threads(1).as[JsArray].value.length === 1
        val notif = unread_threads(1)(0)
        (notif \ "title").as[String] === "A robot just grumbled! He must be depressed..."
        (notif \ "unread").as[Boolean] === true
        (notif \ "unreadMessages").as[Int] === 1

        val externalId = (notif \ "id").as[String]

        socket.in(Json.arr("set_message_read", externalId))

        val out = socket.out
        out(0).as[String] === "message_read"
        out(2).as[String] === (notif \ "thread").as[String]
      }

    }

  }

}

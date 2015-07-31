package com.keepit.eliza.ws

import java.util.UUID

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.model.{ MessageThread, MessageThreadRepo }
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.SocialUserInfo
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.time.NoTimeConversions
import play.api.libs.json.{ JsArray, Json }
import play.api.mvc.WebSocket
import play.api.test.Helpers._

class ElizaWebSocketTest extends WebSocketTest[JsArray] with ElizaApplicationInjector with NoTimeConversions {

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

  def setupSocialUser(implicit injector: Injector): Unit = {
    val fakeShoeboxServiceClient = inject[ShoeboxServiceClient].asInstanceOf[FakeShoeboxServiceClientImpl]
    fakeShoeboxServiceClient.socialUserInfosByNetworkAndSocialId((FakeSecureSocial.FAKE_SOCIAL_ID, FakeSecureSocial.FAKE_NETWORK_TYPE)) = SocialUserInfo(
      userId = Some(Id(1)),
      fullName = FakeSecureSocial.FAKE_SOCIAL_USER.fullName,
      socialId = FakeSecureSocial.FAKE_SOCIAL_ID,
      networkType = FakeSecureSocial.FAKE_NETWORK_TYPE
    )
  }

  "SharedWsMessagingController" should {

    "connect and disconnect" in {
      running(new ElizaApplication(modules: _*)) {

        setupSocialUser

        socketOutput must equalTo(List(Json.arr("hi"), Json.arr("bye", "session")))

      }
    }

    "respond to ping" in {
      running(new ElizaApplication(modules: _*)) {

        setupSocialUser

        in {
          Json.arr("ping")
        }

        socketOutput must equalTo(List(Json.arr("hi"), Json.arr("pong"), Json.arr("bye", "session")))

      }
    }

    "respond with stats" in {
      running(new ElizaApplication(modules: _*)) {
        setupSocialUser

        in {
          Json.arr("stats")
        }

        val output = socketOutput

        output(1)(0).as[String] must startWith("id:")
      }
    }

    "respond to thread request" in {
      running(new ElizaApplication(modules: _*)) {
        setupSocialUser
        val messageThreadRepo = inject[MessageThreadRepo]
        val uuid = UUID.randomUUID().toString

        db.readWrite { implicit session =>
          messageThreadRepo.save(MessageThread(externalId = ExternalId(uuid), uriId = None, url = None, nUrl = None, pageTitle = None, participants = None, participantsHash = None, replyable = true))
        }

        in {
          Json.arr("get_thread", uuid)
        }

        val output = socketOutput

        output.length === 2
      }
    }

  }

}

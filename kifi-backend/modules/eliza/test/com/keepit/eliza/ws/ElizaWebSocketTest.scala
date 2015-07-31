package com.keepit.eliza.ws

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.SocialUserInfo
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaApplication, ElizaApplicationInjector }
import org.specs2.time.NoTimeConversions
import play.api.libs.json.Json
import play.api.test.Helpers._

class ElizaWebSocketTest extends WebSocketTest with ElizaApplicationInjector with NoTimeConversions {

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
        List() must leadToSocketOutput(equalTo(List(Json.arr("hi"), Json.arr("bye", "session"))))

      }
    }

    "respond to ping" in {
      running(new ElizaApplication(modules: _*)) {

        setupSocialUser
        List(Json.arr("ping")) must leadToSocketOutput(equalTo(List(Json.arr("hi"), Json.arr("pong"), Json.arr("bye", "session"))))

      }
    }

  }

}

package com.keepit.eliza.ws

import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.social.{ FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
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

  "SharedWsMessagingController" should {

    "respond to ping" in {

      running(new ElizaApplication(modules: _*)) {

        List(Json.arr("ping")) must leadToSocketOutput(equalTo(List(Json.arr("pong"))))

      }

    }

  }

}

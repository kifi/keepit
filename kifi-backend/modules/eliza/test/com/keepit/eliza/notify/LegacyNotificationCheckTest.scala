package com.keepit.eliza.notify

import com.google.inject.Injector
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsModule
import com.keepit.common.db.Id
import com.keepit.common.store.FakeElizaStoreModule
import com.keepit.eliza.controllers.shared.SharedWsMessagingController
import com.keepit.eliza.social.{ FakeSecureSocial, FakeSecureSocialUserPluginModule, FakeSecureSocialAuthenticatorPluginModule }
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ User, UserExperimentType, UserExperiment, SocialUserInfo }
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.model.{ UserRecipient, Recipient }
import com.keepit.rover.FakeRoverServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceClientImpl, ShoeboxServiceClient, FakeShoeboxServiceModule }
import com.keepit.test.{ ElizaTestInjector, ElizaApplicationInjector }
import org.specs2.mutable.Specification
import org.specs2.time.NoTimeConversions
import play.api.libs.json.JsArray
import play.api.mvc.WebSocket

class LegacyNotificationCheckTest extends Specification with ElizaTestInjector with NoTimeConversions with WsTestBehavior {

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

  "LegacyNotificationCheck" should {

    "return the right result for enabled experiments" in {
      withDb(modules: _*) { implicit injector =>
        val check = inject[LegacyNotificationCheck]

        setupUserExperiment(enabled = true)

        val result = check.checkUserExperiment(Recipient(Id[User](1)))

        result.experimentEnabled === true
        (result.recipient match {
          case UserRecipient(_, Some(true)) => true
          case _ => false
        }) === true
      }
    }

    "return the right result for non-enabled experiments" in {
      withDb(modules: _*) { implicit injector =>
        val check = inject[LegacyNotificationCheck]

        setupUserExperiment(enabled = false)

        val result = check.checkUserExperiment(Recipient(Id[User](1)))

        result.experimentEnabled === false
        (result.recipient match {
          case UserRecipient(_, Some(false)) => false
          case _ => true
        }) === true
      }

    }

  }

}

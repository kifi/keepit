package com.keepit.controllers.core

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ FakeOutbox, EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.{ UserEmailAddress, UserEmailAddressRepo }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class AuthControllerTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(FakeShoeboxServiceModule(),
    FakeExecutionContextModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeHttpClientModule(),
    FakeMailModule(),
    FakeSearchServiceClientModule(),
    FakeCortexServiceClientModule())

  "AuthController" should {
    val call = com.keepit.controllers.core.routes.AuthController.forgotPassword()

    "correct URL and method" in {
      call.method === "POST"
      call.url === "/password/forgot"
    }

    "reset password with valid email" in {
      withDb(modules: _*) { implicit injector =>
        val user = db.readWrite { implicit rw =>
          val user = UserFactory.user().withName("Elaine", "Benes").withUsername("test").saved
          userEmailAddressCommander.intern(userId = user.id.get, address = EmailAddress("elaine@gmail.com")).get._1
          userEmailAddressCommander.intern(userId = user.id.get, address = EmailAddress("dancing@gmail.com")).get._1
          user
        }
        inject[FakeUserActionsHelper].setUser(user)

        val outbox = inject[FakeOutbox]
        val ctrl = inject[AuthController]
        // test 2 calls for the same user with different valid emails
        val result1 = ctrl.forgotPassword()(FakeRequest(call).withBody(Json.obj("email" -> "elaine@gmail.com")))
        Await.ready(result1, Duration(5, "seconds"))

        outbox.size === 1
        outbox(0).to === Seq(EmailAddress("elaine@gmail.com"))
        Json.parse(contentAsString(result1)) === Json.obj("addresses" -> Json.toJson(Seq("elaine@gmail.com")))
        status(result1) === OK

        val result2 = ctrl.forgotPassword()(FakeRequest(call).withBody(Json.obj("email" -> "dancing@gmail.com")))
        Await.ready(result2, Duration(5, "seconds"))

        outbox.size === 3
        val actual = (outbox(1).to ++ outbox(2).to).sortBy(_.address)
        val expected = Seq("dancing@gmail.com", "elaine@gmail.com").map(EmailAddress(_))
        actual === expected
        Json.parse(contentAsString(result2)) === Json.obj("addresses" -> Json.toJson(Seq("dancing@gmail.com", "e...@gmail.com")))
        status(result2) === OK
      }
    }
    "reset password with invalid email" in {
      withDb(modules: _*) { implicit injector =>
        val ctrl = inject[AuthController]
        val body = Json.obj("email" -> "foo@bar.com")
        val result = ctrl.forgotPassword()(FakeRequest(call).withBody(body))
        Json.parse(contentAsString(result)) === Json.obj("error" -> "no_account")
        status(result) === BAD_REQUEST
      }
    }
  }
}

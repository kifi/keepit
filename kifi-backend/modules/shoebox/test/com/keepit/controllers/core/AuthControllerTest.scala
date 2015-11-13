package com.keepit.controllers.core

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.time._

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.{ FakeOutbox, EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.{ EmailVerificationCode, UserEmailAddress, UserEmailAddressRepo, UserFactory }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.{ ShoeboxApplicationInjector, ShoeboxApplication, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import com.keepit.model.UserFactoryHelper._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class AuthControllerTest extends Specification with ShoeboxApplicationInjector {

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

    "correct URL and method" in {
      val call = com.keepit.controllers.core.routes.AuthController.forgotPassword()
      call.method === "POST"
      call.url === "/password/forgot"
    }

    "reset password with valid email" in {
      running(new ShoeboxApplication(modules: _*)) {
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
        val call = com.keepit.controllers.core.routes.AuthController.forgotPassword()
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
      running(new ShoeboxApplication(modules: _*)) {
        val ctrl = inject[AuthController]
        val body = Json.obj("email" -> "foo@bar.com")
        val call = com.keepit.controllers.core.routes.AuthController.forgotPassword()
        val result = ctrl.forgotPassword()(FakeRequest(call).withBody(body))
        Json.parse(contentAsString(result)) === Json.obj("error" -> "no_account")
        status(result) === BAD_REQUEST
      }
    }
    "verify email invalid code" in {
      running(new ShoeboxApplication(modules: _*)) {
        val ctrl = inject[AuthController]
        val code = EmailVerificationCode("some_code")
        val call = com.keepit.controllers.core.routes.AuthController.verifyEmail(code)
        val result = ctrl.verifyEmail(code)(FakeRequest(call))
        header("Location", result) === None
        status(result) === BAD_REQUEST
      }
    }
    "verify email good code bad user" in {
      running(new ShoeboxApplication(modules: _*)) {
        val ctrl = inject[AuthController]
        val address = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          inject[UserEmailAddressRepo].save(UserEmailAddress.create(user.id.get, EmailAddress("eishay@kifi.com")).withVerificationCode(currentDateTime))
        }
        val code = address.verificationCode.get
        val call = com.keepit.controllers.core.routes.AuthController.verifyEmail(code)
        val result = ctrl.verifyEmail(code)(FakeRequest(call))
        header("Location", result) === Some("/login")
        status(result) === SEE_OTHER
      }
    }
    "verify email good code bad user yes mobile" in {
      running(new ShoeboxApplication(modules: _*)) {
        val ctrl = inject[AuthController]
        val address = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          inject[UserEmailAddressRepo].save(UserEmailAddress.create(user.id.get, EmailAddress("eishay@kifi.com")).withVerificationCode(currentDateTime))
        }
        val code = address.verificationCode.get
        val call = com.keepit.controllers.core.routes.AuthController.verifyEmail(code)
        val result = ctrl.verifyEmail(code)(FakeRequest(call).withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3"))
        header("Location", result) === None
        status(result) === OK
      }
    }
    "verify email good code good user no installation" in {
      running(new ShoeboxApplication(modules: _*)) {
        val ctrl = inject[AuthController]
        val (address, user) = db.readWrite { implicit s =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          val address = inject[UserEmailAddressRepo].save(UserEmailAddress.create(user.id.get, EmailAddress("eishay@kifi.com")).withVerificationCode(currentDateTime))
          address.verified === false
          (address, user)
        }
        inject[FakeUserActionsHelper].setUser(user)
        val code = address.verificationCode.get
        val call = com.keepit.controllers.core.routes.AuthController.verifyEmail(code)
        val result = ctrl.verifyEmail(code)(FakeRequest(call))
        header("Location", result) === Some("/install")
        status(result) === SEE_OTHER
        db.readOnlyMaster { implicit s =>
          val addresses = inject[UserEmailAddressRepo].getAllByUser(user.id.get)
          addresses.size === 1
          addresses.head.verified === true
        }
      }
    }
  }
}

package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.KifiSession._
import com.keepit.common.controller._
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule }
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.oauth.FakeOAuth2ConfigurationModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.UserStates
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Request, AnyContent, Result }
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future

class PasswordSignupTest extends Specification with ShoeboxApplicationInjector {

  implicit val context = HeimdalContext.empty

  def modules = Seq(
    FakeExecutionContextModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeShoeboxServiceModule(),
    FakeSearchServiceClientModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeABookServiceClientModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeShoeboxAppSecureSocialModule(),
    MaybeAppFakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    KeepImportsModule(),
    FakeCuratorServiceClientModule(),
    FakeOAuth2ConfigurationModule()
  )

  "AuthController" should {

    "sign-up++ via userpass" in {
      running(new ShoeboxApplication(modules: _*)) {
        inject[MaybeAppFakeUserActionsHelper].removeUser()
        val authController = inject[AuthController]

        // 1. sign-up
        val path = "/auth/sign-up"
        val fooEmail = EmailAddress("foo@bar.com")
        val fooPwd = "1234567"
        val request = FakeRequest("POST", path).withBody(Json.obj("email" -> fooEmail.address, "password" -> fooPwd))
        val result = authController.userPasswordSignup()(request)
        status(result) === OK
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("success" -> true).toString()
        val session1 = session(result)
        session1.getUserId.isDefined === true
        val userId = session1.getUserId.get
        val cookies1 = cookies(result)

        val (user, suiOpt) = db.readOnlyMaster { implicit s =>
          val user = userRepo.get(userId)
          val suiOpt = socialUserInfoRepo.getByUser(userId).headOption
          (user, suiOpt)
        }
        user.state === UserStates.INCOMPLETE_SIGNUP
        suiOpt.isDefined === true

        // 2. finalize
        val path2 = "/auth/email-finalize"
        val request2 = FakeRequest("POST", path2).withBody(Json.obj("firstName" -> "Foo", "lastName" -> "Bar")).withSession(session1.data.toSeq: _*).withCookies(cookies1.toSeq: _*)
        val result2 = authController.userPassFinalizeAccountAction()(request2)
        status(result2) === OK
        contentType(result2) must beSome("application/json")
        contentAsString(result2) === Json.obj("uri" -> "/").toString
        val session2 = session(result2)
        val cookies2 = cookies(result2)
        session2.getUserId.get === userId
        contentAsString(result2) !== ""

        val (user2, suiOpt2) = db.readOnlyMaster { implicit s =>
          val user = userRepo.get(userId)
          val suiOpt = socialUserInfoRepo.getByUser(userId).headOption
          (user, suiOpt)
        }
        user2.state === UserStates.ACTIVE
        suiOpt2.isDefined === true

        // 3. logout
        val path3 = "/logout"
        val request3 = FakeRequest("GET", path3).withSession(session2.data.toSeq: _*).withCookies(cookies2.toSeq: _*)
        val result3 = com.keepit.social.providers.LoginPage.logout()(request3)
        status(result3) === SEE_OTHER // redirect
        redirectLocation(result3) must beSome("/")
        val session3 = session(result3)
        val cookies3 = cookies(result3)
        session3.getUserId.isEmpty === true

        // 4. re-login
        val path4 = "/auth/log-in"
        val request4 = FakeRequest("POST", path4).withBody(Json.obj("username" -> fooEmail.address, "password" -> "1234567")).withSession(session3.data.toSeq: _*).withCookies(cookies3.toSeq: _*)
        val result4: Future[Result] = authController.logInWithUserPass("")(request4.asInstanceOf[Request[AnyContent]])
        status(result4) === OK // client fetch
        val session4 = session(result4)
        val cookies4 = cookies(result4)
        contentAsString(result4) === Json.obj("uri" -> "/login/after").toString
        session4.getUserId.isDefined === true
        session4.getUserId.get === userId
      }
    }

    "one-step signup (simple)" in {
      running(new ShoeboxApplication(modules: _*)) {
        inject[MaybeAppFakeUserActionsHelper].removeUser()
        val authController = inject[AuthController]
        val path = "/auth/email-signup"
        val fooEmail = EmailAddress("foo@bar.com")
        val payload = Json.obj("email" -> fooEmail.address, "password" -> "1234567", "firstName" -> "Foo", "lastName" -> "Bar")
        val request = FakeRequest("POST", path).withBody(payload)
        val result = authController.emailSignup()(request)
        status(result) === OK
        contentType(result) must beSome("application/json")
        contentAsString(result) === Json.obj("uri" -> "/").toString()
        val sess = session(result)
        sess.getUserId.isDefined === true
        val userId = sess.getUserId.get
        val (user, suiOpt) = db.readOnlyMaster { implicit s =>
          val user = userRepo.get(userId)
          val suiOpt = socialUserInfoRepo.getByUser(userId).headOption
          (user, suiOpt)
        }
        user.state === UserStates.ACTIVE
        suiOpt.isDefined === true

        // (wrong) sign-up
        val payload1 = Json.obj("email" -> fooEmail.address, "password" -> "wrongpwd", "firstName" -> "NotFoo", "lastName" -> "NotBar")
        val request1 = FakeRequest("POST", path).withBody(payload1)
        val result1 = authController.emailSignup()(request1)
        status(result1) === FORBIDDEN
        contentType(result1) must beSome("application/json")
        contentAsString(result1) === Json.obj("error" -> "user_exists_failed_auth").toString()
        session(result1).getUserId.isDefined === false
      }
    }

  }
}

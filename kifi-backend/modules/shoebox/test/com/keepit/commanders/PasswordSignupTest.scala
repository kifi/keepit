package com.keepit.commanders

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.KifiSession._
import com.keepit.common.controller._
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.{ FakeAirbrakeModule, FakeHealthcheckModule }
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.core.AuthController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model.UserStates
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, KeepImportsModule }
import com.keepit.test.{ ShoeboxApplication, ShoeboxApplicationInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._

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
    FakeScrapeSchedulerModule(),
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
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    KeepImportsModule(),
    FakeCuratorServiceClientModule()
  )

  "AuthController" should {

    "sign-up via userpass" in {
      running(new ShoeboxApplication(modules: _*)) {
        inject[MaybeAppFakeUserActionsHelper].removeUser()
        val authController = inject[AuthController]

        // 1. sign-up
        val path = "/auth/sign-up"
        val fooEmail = EmailAddress("foo@bar.com")
        val request = FakeRequest("POST", path).withBody(Json.obj("email" -> fooEmail.address, "password" -> "1234567"))
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
        session(result2).getUserId.get === userId
        contentAsString(result2) !== ""

        val (user2, suiOpt2) = db.readOnlyMaster { implicit s =>
          val user = userRepo.get(userId)
          val suiOpt = socialUserInfoRepo.getByUser(userId).headOption
          (user, suiOpt)
        }
        user2.state === UserStates.ACTIVE
        suiOpt2.isDefined === true
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
        val session1 = session(result)
        session1.getUserId.isDefined === true
        val userId = session1.getUserId.get
        val cookies1 = cookies(result)
        val (user, suiOpt) = db.readOnlyMaster { implicit s =>
          val user = userRepo.get(userId)
          val suiOpt = socialUserInfoRepo.getByUser(userId).headOption
          (user, suiOpt)
        }
        user.state === UserStates.ACTIVE
        suiOpt.isDefined === true
      }
    }

  }
}

package com.keepit.controllers.admin

import org.specs2.mutable.Specification

import com.keepit.common.social.TestShoeboxAppSecureSocialModule
import com.keepit.common.controller.FortyTwoCookies.{ ImpersonateCookie, KifiInstallationCookie }
import com.keepit.social.{ SocialId, SocialNetworks }
import SocialNetworks.FACEBOOK
import com.keepit.model._
import com.keepit.test._
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.mail.{ FakeOutbox, FakeMailModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class AdminAuthControllerTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeHttpClientModule(),
    FakeMailModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule(),
    TestShoeboxAppSecureSocialModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule())

  "AdminAuthController" should {
    "impersonate" in {
      running(new ShoeboxApplication(modules: _*)) {
        inject[FakeOutbox].size === 0
        val su1 = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(OAuth2Info(accessToken = "A")), None)
        val su2 = SocialUser(IdentityId("222", "facebook"), "B", "1", "B 1", Some("b1@gmail.com"),
          Some("http://www.fb.com/him"), AuthenticationMethod.OAuth2, None, Some(OAuth2Info(accessToken = "B")), None)
        val (admin, impersonate) = db.readWrite { implicit s =>
          val admin = userRepo.save(User(firstName = "A", lastName = "1"))
          socialUserInfoRepo.save(SocialUserInfo(userId = admin.id, fullName = "A 1", socialId = SocialId("111"),
            networkType = FACEBOOK, credentials = Some(su1)))
          val impersonate = userRepo.save(User(firstName = "B", lastName = "1"))
          socialUserInfoRepo.save(SocialUserInfo(userId = impersonate.id, fullName = "B 1",
            socialId = SocialId("222"), networkType = FACEBOOK, credentials = Some(su2)))
          (admin, impersonate)
        }
        val cookie1 = Authenticator.create(su1).right.get.toCookie
        val cookie2 = Authenticator.create(su2).right.get.toCookie

        val startRequest = FakeRequest("POST", "/kifi/start")
          .withCookies(cookie1)
          .withJsonBody(JsObject(Seq("agent" -> JsString("test agent"), "version" -> JsString("0.0.0"))))
        val startResult = route(startRequest).get
        status(startResult) must equalTo(200)
        val sessionCookie = session(startResult)
        val impersonateCookie = inject[ImpersonateCookie]
        cookies(startResult).get(impersonateCookie.COOKIE_NAME) === None
        cookies(startResult).get(inject[KifiInstallationCookie].COOKIE_NAME) !== None

        val meRequest1 = FakeRequest("GET", "/test/me").withCookies(cookie1)
        val meResult1 = route(meRequest1).get

        contentAsString(meResult1) === admin.externalId.toString

        val impersonateRequest = FakeRequest("POST", "/admin/user/%s/impersonate".format(impersonate.id.get.toString))
          .withCookies(cookie1)
        val impersonateResultFail = route(impersonateRequest).get
        status(impersonateResultFail) must equalTo(403)

        db.readWrite { implicit s =>
          inject[UserExperimentRepo].save(UserExperiment(experimentType = ExperimentType.ADMIN, userId = admin.id.get))
        }
        val impersonateResult = route(impersonateRequest).get
        //status(impersonateResult) must equalTo(200)
        val imprSessionCookie = session(impersonateResult)
        //        impersonateCookie.decodeFromCookie(cookies(impersonateResult).get(impersonateCookie.COOKIE_NAME)) === Some(impersonate.externalId)

        //        val meRequest2 = FakeRequest("GET", "/test/me")
        //            .withCookies(cookie1, cookies(impersonateResult)(impersonateCookie.COOKIE_NAME))
        //        val meResult2 = route(meRequest2).get
        //        contentAsString(meResult2) === impersonate.externalId.toString

        //        inject[FakeOutbox].size === 1

        val unimpersonateRequest = FakeRequest("POST", "/admin/unimpersonate")
          .withCookies(cookie1)
        val unimpersonateResult = route(unimpersonateRequest).get
        impersonateCookie.decodeFromCookie(cookies(unimpersonateResult).get(impersonateCookie.COOKIE_NAME)) === None

        //        val meRequest3 = FakeRequest("GET", "/test/me")
        //            .withCookies(cookie1, cookies(unimpersonateResult)(impersonateCookie.COOKIE_NAME))
        //        val meResult3 = route(meRequest3).get
        //        contentAsString(meResult3) === admin.externalId.toString

      }
    }
  }
}

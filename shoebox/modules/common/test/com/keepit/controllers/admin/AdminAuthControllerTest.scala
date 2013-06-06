package com.keepit.controllers.admin

import org.specs2.mutable.Specification

import com.keepit.common.controller.ActionAuthenticator
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.inject._
import com.keepit.model._
import com.keepit.test._

import play.api.Play.current
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._

class AdminAuthControllerTest extends Specification with DbRepos {

  args(skipAll = true) // todo(Andrew/Greg/anyone) Fix this!!!!!

  //todo(eishay) refactor commonalities out of this one and AdminDashboardController to make this test easy to write
  "AdminAuthController" should {
    "impersonate" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val su1 = SocialUser(UserId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(OAuth2Info(accessToken = "A")), None)
        val su2 = SocialUser(UserId("222", "facebook"), "B", "1", "B 1", Some("b1@gmail.com"),
          Some("http://www.fb.com/him"), AuthenticationMethod.OAuth2, None, Some(OAuth2Info(accessToken = "B")), None)
        val (admin, impersonate) = db.readWrite {implicit s =>
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
        sessionCookie(ActionAuthenticator.FORTYTWO_USER_ID) === admin.id.get.toString
        cookies(startResult).get(impersonateCookie.COOKIE_NAME) === None
        cookies(startResult).get(inject[KifiInstallationCookie].COOKIE_NAME) !== None

        val whoisRequest1 = FakeRequest("GET", "/whois").withCookies(cookie1)
        val whoisResult1 = route(whoisRequest1).get

        (Json.parse(contentAsString(whoisResult1)) \ "externalUserId").as[String] === admin.externalId.toString

        val impersonateRequest = FakeRequest("POST", "/admin/user/%s/impersonate".format(impersonate.id.get.toString))
          .withCookies(cookie1)
        val impersonateResultFail = route(impersonateRequest).get
        status(impersonateResultFail) must equalTo(401)

        db.readWrite {implicit s =>
          inject[UserExperimentRepo].save(UserExperiment(experimentType = ExperimentTypes.ADMIN, userId = admin.id.get))
        }
        val impersonateResult = route(impersonateRequest).get
        val imprSessionCookie = session(impersonateResult)
        imprSessionCookie(ActionAuthenticator.FORTYTWO_USER_ID) === admin.id.get.toString
        impersonateCookie.decodeFromCookie(cookies(impersonateResult).get(impersonateCookie.COOKIE_NAME)) === Some(impersonate.externalId)

        val whoisRequest2 = FakeRequest("GET", "/whois")
            .withCookies(cookie1, cookies(impersonateResult)(impersonateCookie.COOKIE_NAME))
        val whoisResult2 = route(whoisRequest2).get
        (Json.parse(contentAsString(whoisResult2)) \ "externalUserId").as[String] === impersonate.externalId.toString

        val unimpersonateRequest = FakeRequest("POST", "/admin/unimpersonate")
            .withCookies(cookie1)
        val unimpersonateResult = route(unimpersonateRequest).get
        impersonateCookie.decodeFromCookie(cookies(unimpersonateResult).get(impersonateCookie.COOKIE_NAME)) === None

        val whoisRequest3 = FakeRequest("GET", "/whois")
            .withCookies(cookie1, cookies(unimpersonateResult)(impersonateCookie.COOKIE_NAME))
        val whoisResult3 = route(whoisRequest3).get
        (Json.parse(contentAsString(whoisResult3)) \ "externalUserId").as[String] === admin.externalId.toString

      }
    }
  }
}

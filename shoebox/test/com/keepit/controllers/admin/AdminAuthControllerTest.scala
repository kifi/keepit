package com.keepit.controllers.admin

import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.Play.current
import play.api.libs.json._
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.test.FakeHeaders
import com.keepit.inject._
import com.keepit.common.social.SocialId
import com.keepit.common.db._
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.controller.FortyTwoController.ImpersonateCookie
import com.keepit.common.controller.FortyTwoController.KifiInstallationCookie
import com.keepit.model._
import com.keepit.model.ExperimentTypes.ADMIN
import com.keepit.test.FakeClock
import securesocial.core.SecureSocial
import com.keepit.social.SecureSocialUserService
import securesocial.core.UserService
import securesocial.core.OAuth2Info
import securesocial.core.SocialUser
import securesocial.core.UserId
import securesocial.core.AuthenticationMethod
import org.joda.time.LocalDate
import org.joda.time.DateTime

class AdminAuthControllerTest extends Specification with DbRepos {

  //todo(eishay) refactor commonalities out of this one and AdminDashboardController to make this test easy to write
  "AdminAuthController" should {
    "impersonate" in {
      running(new EmptyApplication().withFakeSecureSocialUserService().withFakeHealthcheck()) {
        val (admin, impersonate) = db.readWrite {implicit s =>
          val admin = userRepo.save(User(firstName = "A", lastName = "1"))
          socialUserInfoRepo.save(SocialUserInfo(userId = admin.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK, credentials = Some(SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(OAuth2Info(accessToken = "A")), None))))
          val impersonate = userRepo.save(User(firstName = "B", lastName = "1"))
          socialUserInfoRepo.save(SocialUserInfo(userId = impersonate.id, fullName = "B 1", socialId = SocialId("222"), networkType = FACEBOOK, credentials = Some(SocialUser(UserId("222", "facebook"), "B 1", Some("b1@gmail.com"), Some("http://www.fb.com/him"), AuthenticationMethod.OAuth2, true, None, Some(OAuth2Info(accessToken = "B")), None))))
          (admin, impersonate)
        }
        val startRequest = FakeRequest("POST", "/kifi/start").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withJsonBody(JsObject(Seq("agent" -> JsString("test agent"), "version" -> JsString("0.0.0"))))
        val startResult = route(startRequest).get
        status(startResult) must equalTo(200)
        val sessionCookie = session(startResult)
        sessionCookie(FortyTwoController.FORTYTWO_USER_ID) === admin.id.get.toString
        sessionCookie("securesocial.user") === "111"
        sessionCookie("securesocial.provider") === "facebook"
        cookies(startResult).get(ImpersonateCookie.COOKIE_NAME) === None
        cookies(startResult).get(KifiInstallationCookie.COOKIE_NAME) !== None

        val whoisRequest1 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val whoisResult1 = route(whoisRequest1).get

        (Json.parse(contentAsString(whoisResult1)) \ "externalUserId").as[String] === admin.externalId.toString

        val impersonateRequest = FakeRequest("POST", "/admin/user/%s/impersonate".format(impersonate.id.get.toString)).
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val impersonateResultFail = route(impersonateRequest).get
        status(impersonateResultFail) must equalTo(401)

        db.readWrite {implicit s =>
          inject[UserExperimentRepo].save(UserExperiment(experimentType = ExperimentTypes.ADMIN, userId = admin.id.get))
        }
        val impersonateResult = route(impersonateRequest).get
        val imprSessionCookie = session(impersonateResult)
        imprSessionCookie(FortyTwoController.FORTYTWO_USER_ID) === admin.id.get.toString
        imprSessionCookie("securesocial.user") === "111"
        imprSessionCookie("securesocial.provider") === "facebook"
        ImpersonateCookie.decodeFromCookie(cookies(impersonateResult).get(ImpersonateCookie.COOKIE_NAME)) === Some(impersonate.externalId)

        val whoisRequest2 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString).
            withCookies(cookies(impersonateResult)(ImpersonateCookie.COOKIE_NAME))
        val whoisResult2 = route(whoisRequest2).get
        (Json.parse(contentAsString(whoisResult2)) \ "externalUserId").as[String] === impersonate.externalId.toString

        val unimpersonateRequest = FakeRequest("POST", "/admin/unimpersonate").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val unimpersonateResult = route(unimpersonateRequest).get
        ImpersonateCookie.decodeFromCookie(cookies(unimpersonateResult).get(ImpersonateCookie.COOKIE_NAME)) === None

        val whoisRequest3 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString).
            withCookies(cookies(unimpersonateResult)(ImpersonateCookie.COOKIE_NAME))
        val whoisResult3 = route(whoisRequest3).get
        (Json.parse(contentAsString(whoisResult3)) \ "externalUserId").as[String] === admin.externalId.toString

      }
    }
  }
}

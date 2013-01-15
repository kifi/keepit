package com.keepit.controllers

import com.keepit.test.EmptyApplication
import org.junit.runner.RunWith
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
import play.api.Play.current
import play.api.mvc._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import play.api.test.FakeHeaders
import play.api.libs.json.Json
import play.api.libs.json.JsArray
import play.api.libs.json.JsObject
import play.api.libs.json.JsValue
import com.keepit.inject._
import com.keepit.common.social.SocialId
import com.keepit.common.db.CX
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.common.controller.FortyTwoController
import com.keepit.common.controller.FortyTwoController.ImpersonateCookie
import com.keepit.common.controller.FortyTwoController.KifiInstallationCookie
import com.keepit.model.{User, UserCxRepo}
import com.keepit.model.UserExperiment
import com.keepit.model.UserExperiment.ExperimentTypes.ADMIN
import com.keepit.model.SocialUserInfo
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
import com.keepit.model.{KifiInstallation, KifiInstallationCxRepo}
import com.keepit.common.db.ExternalId

@RunWith(classOf[JUnitRunner])
class AuthControllerTest extends SpecificationWithJUnit {

  //todo(eishay) refactor commonalities out of this one and AdminDashboardController to make this test easy to write
  "AuthController" should {

    "impersonate" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val (admin, impersonate) = CX.withConnection { implicit c =>
          val admin = User(firstName = "A", lastName = "1").save
          SocialUserInfo(userId = admin.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK, credentials = Some(SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"), Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(OAuth2Info(accessToken = "A")), None))).save
          val impersonate = User(firstName = "B", lastName = "1").save
          SocialUserInfo(userId = impersonate.id, fullName = "B 1", socialId = SocialId("222"), networkType = FACEBOOK, credentials = Some(SocialUser(UserId("222", "facebook"), "B 1", Some("b1@gmail.com"), Some("http://www.fb.com/him"), AuthenticationMethod.OAuth2, true, None, Some(OAuth2Info(accessToken = "B")), None))).save
          (admin, impersonate)
        }
        val startRequest = FakeRequest("POST", "/kifi/start").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withFormUrlEncodedBody(("agent" -> "test agent"), ("version" -> "0.0.0"))
        val startResult = routeAndCall(startRequest).get
        status(startResult) must equalTo(200)
        val sessionCookie = session(startResult)
        sessionCookie(FortyTwoController.FORTYTWO_USER_ID) === admin.id.get.toString
        sessionCookie("securesocial.user") === "111"
        sessionCookie("securesocial.provider") === "facebook"
        cookies(startResult).get(ImpersonateCookie.COOKIE_NAME) === None
        cookies(startResult).get(KifiInstallationCookie.COOKIE_NAME) !== None

        val whoisRequest1 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val whoisResult1 = routeAndCall(whoisRequest1).get
        (Json.parse(contentAsString(whoisResult1)) \ "externalUserId").as[String] === admin.externalId.toString

        val impersonateRequest = FakeRequest("POST", "/admin/user/%s/impersonate".format(impersonate.id.get.toString)).
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val impersonateResultFail = routeAndCall(impersonateRequest).get
        status(impersonateResultFail) must equalTo(401)

        CX.withConnection { implicit c =>
          UserExperiment(UserExperiment.ExperimentTypes.ADMIN, admin.id.get).save
        }
        val impersonateResult = routeAndCall(impersonateRequest).get
        val imprSessionCookie = session(impersonateResult)
        imprSessionCookie(FortyTwoController.FORTYTWO_USER_ID) === admin.id.get.toString
        imprSessionCookie("securesocial.user") === "111"
        imprSessionCookie("securesocial.provider") === "facebook"
        ImpersonateCookie.decodeFromCookie(cookies(impersonateResult).get(ImpersonateCookie.COOKIE_NAME)) === Some(impersonate.externalId)

        val whoisRequest2 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString).
            withCookies(cookies(impersonateResult)(ImpersonateCookie.COOKIE_NAME))
        val whoisResult2 = routeAndCall(whoisRequest2).get
        (Json.parse(contentAsString(whoisResult2)) \ "externalUserId").as[String] === impersonate.externalId.toString

        val unimpersonateRequest = FakeRequest("POST", "/admin/unimpersonate").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString)
        val unimpersonateResult = routeAndCall(unimpersonateRequest).get
        ImpersonateCookie.decodeFromCookie(cookies(unimpersonateResult).get(ImpersonateCookie.COOKIE_NAME)) === None

        val whoisRequest3 = FakeRequest("GET", "/whois").
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook", "userId" -> admin.id.get.toString).
            withCookies(cookies(unimpersonateResult)(ImpersonateCookie.COOKIE_NAME))
        val whoisResult3 = routeAndCall(whoisRequest3).get
        (Json.parse(contentAsString(whoisResult3)) \ "externalUserId").as[String] === admin.externalId.toString

      }
    }

    "start" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val user = CX.withConnection { implicit c =>
          val user = User(createdAt = now.minusDays(3), firstName = "A", lastName = "1").save

          val oAuth2Info = OAuth2Info(accessToken = "A",
            tokenType = None, expiresIn = None, refreshToken = None)
          val su = SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"),
            Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          val sui = SocialUserInfo(
              userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su))
            .save
          user
        }

        //first round
        val fakeRequest1 = FakeRequest().
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withFormUrlEncodedBody(("agent" -> "crome agent"), ("version" -> "1.1.1"), ("installation" -> ""))
        val authRequest1 = AuthController.AuthenticatedRequest(null, user.id.get, fakeRequest1)
        val result1 = AuthController.start(authRequest1)
        status(result1) must equalTo(OK)
        val kifiInstallation1 = CX.withConnection { implicit c =>
          val all = KifiInstallationCxRepo.all
          all.size === 1
          all.head
        }
        Json.parse(contentAsString(result1)) === Json.parse("""{"avatarUrl":"http://www.fb.com/me","name":"A 1","facebookId":"111","provider":"facebook","userId":"%s","installationId":"%s"}""".format(user.externalId, kifiInstallation1.externalId.id))

        //second round
        val fakeRequest2 = FakeRequest().
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withFormUrlEncodedBody(("agent" -> "crome agent"), ("version" -> "1.1.1"), ("installation" -> kifiInstallation1.externalId.id))
        val authRequest2 = AuthController.AuthenticatedRequest(null, user.id.get, fakeRequest2)
        val result2 = AuthController.start(authRequest2)
        status(result2) must equalTo(OK)
        val kifiInstallation2 = CX.withConnection { implicit c =>
          val all = KifiInstallationCxRepo.all
          all.size === 1
          all.head
        }
        Json.parse(contentAsString(result2)) === Json.parse("""{"avatarUrl":"http://www.fb.com/me","name":"A 1","facebookId":"111","provider":"facebook","userId":"%s","installationId":"%s"}""".format(user.externalId, kifiInstallation2.externalId.id))
        kifiInstallation1 === kifiInstallation2
      }
    }
  }

}

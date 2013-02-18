package com.keepit.controllers

import com.keepit.test._
import org.junit.runner.RunWith
import org.specs2.mutable.SpecificationWithJUnit
import org.specs2.runner.JUnitRunner
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

@RunWith(classOf[JUnitRunner])
class AuthControllerTest extends SpecificationWithJUnit with DbRepos {

  //todo(eishay) refactor commonalities out of this one and AdminDashboardController to make this test easy to write
  "AuthController" should {
    "impersonate" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
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

    "start" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val user = db.readWrite {implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))

          val oAuth2Info = OAuth2Info(accessToken = "A",
            tokenType = None, expiresIn = None, refreshToken = None)
          val su = SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"),
            Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          val sui = socialUserInfoRepo.save(SocialUserInfo(
              userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su)))
          user
        }

        //first round
        val fakeRequest1 = FakeRequest().
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withJsonBody(JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"))))
        val authRequest1 = AuthController.AuthenticatedRequest(null, user.id.get, fakeRequest1)
        val result1 = AuthController.start(authRequest1)
        status(result1) must equalTo(OK)
        val kifiInstallation1 = db.readOnly {implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        val json1 = Json.parse(contentAsString(result1)).asInstanceOf[JsObject]
        json1 \ "avatarUrl" === JsString("http://www.fb.com/me")
        json1 \ "name" === JsString("A 1")
        json1 \ "facebookId" === JsString("111")
        json1 \ "provider" === JsString("facebook")
        json1 \ "userId" === JsString(user.externalId.id)
        json1 \ "installationId" === JsString(kifiInstallation1.externalId.id)
        json1 \ "rules" \ "version" must beAnInstanceOf[JsString]
        json1 \ "rules" \ "rules" must beAnInstanceOf[JsObject]

        //second round
        val fakeRequest2 = FakeRequest().
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withJsonBody(JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"), "installation" -> JsString(kifiInstallation1.externalId.id))))
        val authRequest2 = AuthController.AuthenticatedRequest(null, user.id.get, fakeRequest2)
        val result2 = AuthController.start(authRequest2)
        status(result2) must equalTo(OK)
        val kifiInstallation2 = db.readOnly {implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        val json2 = Json.parse(contentAsString(result2))
        json2 === json1
        kifiInstallation1 === kifiInstallation2
      }
    }
  }

}

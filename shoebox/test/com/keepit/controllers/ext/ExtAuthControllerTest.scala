package com.keepit.controllers.ext

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
import com.keepit.social.SecureSocialUserService
import com.keepit.common.controller.AuthenticatedRequest

import securesocial.core.{SecureSocial, UserService, OAuth2Info, SocialUser, UserId, AuthenticationMethod}

import org.joda.time.LocalDate
import org.joda.time.DateTime

class ExtAuthControllerTest extends Specification with DbRepos {

  "ExtAuthController" should {
    "start" in {
      running(new EmptyApplication().withFakeSecureSocialUserService().withFakeHealthcheck()) {
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
            withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"))))
        val authRequest1 = AuthenticatedRequest(null, user.id.get, fakeRequest1)
        val result1 = inject[ExtAuthController].start(authRequest1)
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
        json1 \ "patterns" must beAnInstanceOf[JsArray]

        //second round
        val fakeRequest2 = FakeRequest().
            withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook").
            withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"), "installation" -> JsString(kifiInstallation1.externalId.id))))
        val authRequest2 = AuthenticatedRequest(null, user.id.get, fakeRequest2)
        val result2 = inject[ExtAuthController].start(authRequest2)
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

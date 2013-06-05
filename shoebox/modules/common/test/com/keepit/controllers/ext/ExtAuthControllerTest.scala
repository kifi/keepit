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
import com.keepit.common.controller.FortyTwoCookies.{ImpersonateCookie, KifiInstallationCookie}
import com.keepit.model._
import com.keepit.model.ExperimentTypes.ADMIN
import com.keepit.test.FakeClock
import com.keepit.social.SecureSocialUserService
import com.keepit.common.controller.AuthenticatedRequest

import securesocial.core._

import org.joda.time.LocalDate
import org.joda.time.DateTime
import play.api.libs.json.JsArray
import com.keepit.common.controller.AuthenticatedRequest
import play.api.libs.json.JsString
import scala.Some
import securesocial.core.UserId
import com.keepit.model.User
import securesocial.core.OAuth2Info
import com.keepit.model.SocialUserInfo
import play.api.libs.json.JsObject
import com.keepit.common.social.SocialId

class ExtAuthControllerTest extends Specification with DbRepos {

  "ExtAuthController" should {
    "start" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val oAuth2Info = OAuth2Info(accessToken = "A",
          tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(UserId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val user = db.readWrite {implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))
          val sui = socialUserInfoRepo.save(SocialUserInfo(
              userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su)))
          user
        }

        val cookie = Authenticator.create(su).right.get.toCookie
        //first round
        val fakeRequest1 = FakeRequest()
            .withCookies(cookie)
            .withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"))))
        val authRequest1 = AuthenticatedRequest(null, user.id.get, user, fakeRequest1)
        val result1 = inject[ExtAuthController].start(authRequest1)
        status(result1) must equalTo(OK)
        val kifiInstallation1 = db.readOnly {implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        val json1 = Json.parse(contentAsString(result1)).asInstanceOf[JsObject]
        json1 \ "name" === JsString("A 1")
        json1 \ "facebookId" === JsString("111")
        json1 \ "provider" === JsString("facebook")
        json1 \ "userId" === JsString(user.externalId.id)
        json1 \ "installationId" === JsString(kifiInstallation1.externalId.id)
        json1 \ "rules" \ "version" must beAnInstanceOf[JsString]
        json1 \ "rules" \ "rules" must beAnInstanceOf[JsObject]
        json1 \ "patterns" must beAnInstanceOf[JsArray]

        //second round
        val fakeRequest2 = FakeRequest()
            .withCookies(cookie)
            .withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"),
              "installation" -> JsString(kifiInstallation1.externalId.id))))
        val authRequest2 = AuthenticatedRequest(null, user.id.get, user, fakeRequest2)
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

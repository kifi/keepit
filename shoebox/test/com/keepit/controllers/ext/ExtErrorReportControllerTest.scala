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
import com.keepit.common.healthcheck._

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

class ExtErrorReportControllerTest extends Specification with DbRepos {

  def fakeRequest(json: JsValue) = {
    val oAuth2Info = OAuth2Info(accessToken = "A", tokenType = None, expiresIn = None, refreshToken = None)
    val su = SocialUser(UserId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
      Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
    val user = db.readWrite {implicit s =>
      val user = userRepo.save(User(firstName = "A", lastName = "1"))
      val sui = socialUserInfoRepo.save(SocialUserInfo(
          userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
          credentials = Some(su)))
      user
    }
    AuthenticatedRequest(null, user.id.get, user,
        FakeRequest().withCookies(Authenticator.create(su).right.get.toCookie)
        .withBody[JsValue](json))
  }

  "ExtAuthController" should {
    "start" in {
      running(new EmptyApplication().withFakeSecureSocialUserService()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0

        val requestJson = Json.obj("message" -> JsString("bad thing happened"))
        val result = inject[ExtErrorReportController].addErrorReport(fakeRequest(requestJson))

        if (inject[ExtErrorReportController].enableExtensionErrorReporting) {
          fakeHealthcheck.errorCount() === 1
          status(result) must equalTo(OK)
          val json = Json.parse(contentAsString(result)).asInstanceOf[JsObject]
          val errorExtId = fakeHealthcheck.errors()(0).id
          json \ "errorId" === JsString(errorExtId.id)
        }
        1 === 1
      }
    }
  }
}

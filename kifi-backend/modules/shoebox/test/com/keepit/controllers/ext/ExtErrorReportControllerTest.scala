package com.keepit.controllers.ext

import org.specs2.mutable.Specification

import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.healthcheck._
import com.keepit.common.social.SocialId
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.inject._
import com.keepit.model.SocialUserInfo
import com.keepit.model.User
import com.keepit.test._

import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._

class ExtErrorReportControllerTest extends Specification with ApplicationInjector with InjectedDbRepos {

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
      running(new DeprecatedShoeboxApplication().withFakeSecureSocialUserService().withFakeHealthcheck()) {
        val fakeHealthcheck = inject[FakeHealthcheck]
        fakeHealthcheck.errorCount() === 0

        val requestJson = Json.obj("message" -> JsString("bad thing happened"))
        val result = inject[ExtErrorReportController].addErrorReport(fakeRequest(requestJson))

        fakeHealthcheck.errorCount() === 1
        status(result) must equalTo(OK)
        val json = Json.parse(contentAsString(result)).asInstanceOf[JsObject]
        val errorExtId = fakeHealthcheck.errors()(0).id
        json \ "errorId" === JsString(errorExtId.id)
      }
    }
  }
}

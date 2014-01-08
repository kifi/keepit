package com.keepit.controllers.ext

import org.specs2.mutable.Specification

import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.healthcheck._
import com.keepit.common.social.{FakeSocialGraphModule, FakeShoeboxSecureSocialModule}
import com.keepit.social.{SocialId, SocialNetworks}
import SocialNetworks.FACEBOOK
import com.keepit.model.SocialUserInfo
import com.keepit.model.User
import com.keepit.test._
import com.keepit.heimdal.{TestHeimdalServiceClientModule, FakeHeimdalServiceClientImpl, HeimdalServiceClient}

import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.TestMailModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.FakeScrapeSchedulerModule


class ExtErrorReportControllerTest extends Specification with ShoeboxApplicationInjector {

  def fakeRequest(json: JsValue) = {
    val oAuth2Info = OAuth2Info(accessToken = "A", tokenType = None, expiresIn = None, refreshToken = None)
    val su = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
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

  def requiredModules = Seq(
    FakeShoeboxSecureSocialModule(),
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeAirbrakeModule(),
    TestHeimdalServiceClientModule(),
    TestMailModule()
  )

  "ExtAuthController" should {
    "start" in {
      running(new ShoeboxApplication(requiredModules: _*)) {
        val fakeHeimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
        fakeHeimdal.eventCount === 0

        val requestJson = Json.obj("message" -> JsString("bad thing happened"))
        val result = inject[ExtErrorReportController].addErrorReport(fakeRequest(requestJson))

        fakeHeimdal.eventCount === 1
        status(result) must equalTo(OK)
      }
    }
  }
}

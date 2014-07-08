package com.keepit.controllers.admin

import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.common.controller.AuthenticatedRequest
import com.keepit.common.social.{FakeSocialGraphModule, FakeShoeboxSecureSocialModule}
import com.keepit.social.{ProdShoeboxSecureSocialModule, SocialId, SocialNetworks}
import SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.model.ExperimentType.ADMIN
import com.keepit.model.SocialUserInfo
import com.keepit.model.User
import com.keepit.model.UserExperiment
import com.keepit.test._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.common.mail.TestMailModule
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.{TestScraperServiceClientModule, FakeScrapeSchedulerModule}
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class AdminDashboardControllerTest extends Specification with ShoeboxApplicationInjector {

  def requiredModules = Seq(
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ProdShoeboxSecureSocialModule(),
    FakeHttpClientModule(),
    ShoeboxFakeStoreModule(),
    FakeSocialGraphModule(),
    FakeAirbrakeModule(),
    TestMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule()
  )

  "AdminDashboardController" should {
    "get users by date as JSON" in {
      running(new ShoeboxApplication(requiredModules: _*)) {

        val now = new DateTime(2020, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        inject[FakeClock].setTimeFunction(() => now.getMillis)

        val oAuth2Info = OAuth2Info(
          accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
          tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val u1 = db.readWrite {implicit s =>
          val u1 = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))
          val u2 = userRepo.save(User(createdAt = now.minusDays(1), firstName = "B", lastName = "2"))
          val u3 = userRepo.save(User(createdAt = now.minusDays(1), firstName = "C", lastName = "3"))
          val sui = socialUserInfoRepo.save(SocialUserInfo(
              userId = u1.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su)))
          socialUserInfoRepo.getOpt(SocialId("111"), FACEBOOK) === Some(sui)
          userExperimentRepo.save(UserExperiment(experimentType = ADMIN, userId = u1.id.get))
          u1
        }

        val cookie = Authenticator.create(su).right.get.toCookie
        val fakeRequest = FakeRequest().withCookies(cookie)
        val authRequest = AuthenticatedRequest(null, u1.id.get, u1, fakeRequest)
        val result = inject[AdminDashboardController].usersByDate(authRequest)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        charset(result) must beSome("utf-8")
        Json.parse(contentAsString(result)) === Json.parse("{\"day0\":\"2020-05-28\",\"counts\":[1,0,2,0]}")
      }
    }
  }

}

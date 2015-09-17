package com.keepit.controllers.admin

import com.keepit.common.concurrent.FakeExecutionContextModule
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import com.keepit.common.controller._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.social.{ ProdShoeboxSecureSocialModule, SocialId, SocialNetworks }
import SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.model.UserExperimentType.ADMIN
import com.keepit.model._
import com.keepit.test._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.common.mail.FakeMailModule
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import securesocial.core._
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.model.UserFactoryHelper._

import com.keepit.cortex.FakeCortexServiceClientModule

class AdminDashboardControllerTest extends Specification with ShoeboxApplicationInjector {

  def requiredModules = Seq(
    FakeExecutionContextModule(),
    FakeUserActionsModule(),
    FakeSearchServiceClientModule(),
    ProdShoeboxSecureSocialModule(),
    FakeHttpClientModule(),
    FakeShoeboxStoreModule(),
    FakeSocialGraphModule(),
    FakeAirbrakeModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule()
  )

  "AdminDashboardController" should {
    "get users by date as JSON" in {
      running(new ShoeboxApplication(requiredModules: _*)) {

        val now = new DateTime(2020, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        inject[FakeClock].setTimeValue(now)

        val oAuth2Info = OAuth2Info(
          accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
          tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val u1 = db.readWrite { implicit s =>
          val u1 = UserFactory.user().withCreatedAt(now.minusDays(3)).withName("A", "1").withUsername("test").saved
          val u2 = UserFactory.user().withCreatedAt(now.minusDays(1)).withName("B", "2").withUsername("test2").saved
          val u3 = UserFactory.user().withCreatedAt(now.minusDays(1)).withName("C", "3").withUsername("test3").saved
          val sui = socialUserInfoRepo.save(SocialUserInfo(
            userId = u1.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
            credentials = Some(su)))
          socialUserInfoRepo.getOpt(SocialId("111"), FACEBOOK) === Some(sui)
          userExperimentRepo.save(UserExperiment(experimentType = ADMIN, userId = u1.id.get))
          u1
        }

        val userActionsHelper = inject[UserActionsHelper].asInstanceOf[FakeUserActionsHelper]
        userActionsHelper.setUser(u1, Set(ADMIN))
        val cookie = Authenticator.create(su).right.get.toCookie
        val fakeRequest = FakeRequest().withCookies(cookie)
        val authRequest = UserRequest(fakeRequest, u1.id.get, u1.id, userActionsHelper)
        val result = inject[AdminDashboardController].usersByDate(authRequest)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        charset(result) must beSome("utf-8")
        Json.parse(contentAsString(result)) === Json.parse("{\"day0\":\"2020-05-28\",\"counts\":[1,0,2,0]}")
      }
    }
  }

}

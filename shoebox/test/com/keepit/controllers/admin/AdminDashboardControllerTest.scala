package com.keepit.controllers.admin

import com.keepit.common.db.CX
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
import com.keepit.common.social.SocialNetworks.FACEBOOK
import com.keepit.common.time._
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
class AdminDashboardControllerTest extends SpecificationWithJUnit {

  "AdminDashboardController" should {
    "get users by date as JSON" in {
      running(new EmptyApplication().withFakeSecureSocialUserService().withFakeHealthcheck()) {

        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val u1 = CX.withConnection { implicit c =>
          val u1 = User(createdAt = now.minusDays(3), firstName = "A", lastName = "1").save
          val u2 = User(createdAt = now.minusDays(1), firstName = "B", lastName = "2").save
          val u3 = User(createdAt = now.minusDays(1), firstName = "C", lastName = "3").save

          val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
            tokenType = None, expiresIn = None, refreshToken = None)
          val su = SocialUser(UserId("111", "facebook"), "A 1", Some("a1@gmail.com"),
            Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, true, None, Some(oAuth2Info), None)
          val sui = SocialUserInfo(
              userId = u1.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
              credentials = Some(su))
            .save
          SocialUserInfoCxRepo.getOpt(SocialId("111"), FACEBOOK) === Some(sui)
          UserExperiment(experimentType = ADMIN, userId = u1.id.get).save
          u1
        }

        val fakeRequest = FakeRequest().withSession(SecureSocial.UserKey -> "111", SecureSocial.ProviderKey -> "facebook")
        val authRequest = AdminDashboardController.AuthenticatedRequest(null, u1.id.get, fakeRequest)
        authRequest.session.get(SecureSocial.ProviderKey) === Some("facebook")
        val result = AdminDashboardController.usersByDate(authRequest)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        charset(result) must beSome("utf-8")
        Json.parse(contentAsString(result)) === Json.parse("{\"day0\":\"2012-05-28\",\"counts\":[1,0,2,0]}")
      }
    }
  }

}

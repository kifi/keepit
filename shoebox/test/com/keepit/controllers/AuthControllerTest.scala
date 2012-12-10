package com.keepit.controllers

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
import com.keepit.model.User
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
import com.keepit.model.KifiInstallation
import com.keepit.common.db.ExternalId

@RunWith(classOf[JUnitRunner])
class AuthControllerTest extends SpecificationWithJUnit {

  //todo(eishay) refactor commonalities out of this one and AdminDashboardController to make this test easy to write
  "AuthController" should {

    "start" in {
      running(new EmptyApplication()) {
        new SecureSocialUserService(current).onStart()
        UserService.delegate.isDefined === true

        val now = new DateTime(2012, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val user = CX.withConnection { implicit c =>
          val user = User(createdAt = now.minusDays(3), firstName = "A", lastName = "1").save

          val oAuth2Info = OAuth2Info(accessToken = "AAAHiW1ZC8SzYBAOtjXeZBivJ77eNZCIjXOkkZAZBjfLbaP4w0uPnj0XzXQUi6ib8m9eZBlHBBxmzzFbEn7jrZADmHQ1gO05AkSZBsZAA43RZC9dQZDZD",
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
          val all = KifiInstallation.all
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
          val all = KifiInstallation.all
          all.size === 1
          all.head
        }
        Json.parse(contentAsString(result2)) === Json.parse("""{"avatarUrl":"http://www.fb.com/me","name":"A 1","facebookId":"111","provider":"facebook","userId":"%s","installationId":"%s"}""".format(user.externalId, kifiInstallation2.externalId.id))
        kifiInstallation1 === kifiInstallation2
      }
    }
  }

}

package com.keepit.controllers.ext

import com.keepit.common.controller.{ FakeUserActionsHelper, FakeUserActionsModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.{ FakeSocialGraphModule, FakeShoeboxAppSecureSocialModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ SocialUserInfo, User, Username }
import com.keepit.scraper.{ FakeScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.{ SocialId, SocialNetworks }
import com.keepit.test._

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import play.api.libs.json.{ Json, JsArray, JsObject, JsString, JsValue }
import play.api.test.Helpers._
import play.api.test.FakeRequest

import securesocial.core.{ Authenticator, AuthenticationMethod, IdentityId, OAuth2Info, SocialUser }

class ExtAuthControllerTest extends Specification with ShoeboxApplicationInjector {

  def requiredModules = Seq(
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxAppSecureSocialModule(),
    FakeShoeboxStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHeimdalServiceClientModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeUserActionsModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeCuratorServiceClientModule()
  )

  "ExtAuthController" should {
    "start" in {
      running(new ShoeboxApplication(requiredModules: _*)) {
        val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val oAuth2Info = OAuth2Info(accessToken = "A",
          tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val user = db.readWrite { implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1", username = Username("test"), normalizedUsername = "test", pictureName = Some("0")))
          val sui = socialUserInfoRepo.save(SocialUserInfo(
            userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = SocialNetworks.FACEBOOK,
            credentials = Some(su)))
          user
        }
        inject[FakeUserActionsHelper].setUser(user)

        val cookie = Authenticator.create(su).right.get.toCookie
        //first round
        val fakeRequest1 = FakeRequest()
          .withCookies(cookie)
          .withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"))))
        val result1 = inject[ExtAuthController].start(fakeRequest1)
        status(result1) must equalTo(OK)
        val kifiInstallation1 = db.readOnlyMaster { implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        val json1 = Json.parse(contentAsString(result1)).asInstanceOf[JsObject]
        json1 \ "user" \ "firstName" === JsString("A")
        json1 \ "user" \ "lastName" === JsString("1")
        json1 \ "user" \ "id" === JsString(user.externalId.id)
        json1 \ "libraryIds" must beAnInstanceOf[JsArray]
        json1 \ "installationId" === JsString(kifiInstallation1.externalId.id)
        json1 \ "rules" \ "version" must beAnInstanceOf[JsString]
        json1 \ "rules" \ "rules" must beAnInstanceOf[JsObject]
        json1 \ "patterns" must beAnInstanceOf[JsArray]

        //second round
        val fakeRequest2 = FakeRequest()
          .withCookies(cookie)
          .withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"),
            "installation" -> JsString(kifiInstallation1.externalId.id))))
        val result2 = inject[ExtAuthController].start(fakeRequest2)
        status(result2) must equalTo(OK)
        val kifiInstallation2 = db.readOnlyMaster { implicit s =>
          val all = installationRepo.all()(s)
          all.size === 1
          all.head
        }
        val json2 = Json.parse(contentAsString(result2))
        json2 === json1
        kifiInstallation1.id === kifiInstallation2.id
      }
    }
  }
}

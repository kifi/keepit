package com.keepit.controllers.ext

import com.keepit.abook.TestABookServiceClientModule
import com.keepit.test._
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test.FakeRequest
import com.keepit.social.{ SocialId, SocialNetworks }
import SocialNetworks.FACEBOOK
import com.keepit.common.time._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import securesocial.core._
import org.joda.time.DateTime
import play.api.libs.json.JsArray
import com.keepit.common.controller.{ FakeActionAuthenticator, AuthenticatedRequest }
import play.api.libs.json.JsString
import scala.Some
import securesocial.core.IdentityId
import com.keepit.model.User
import securesocial.core.OAuth2Info
import com.keepit.model.SocialUserInfo
import play.api.libs.json.JsObject
import com.keepit.common.social.{ FakeSocialGraphModule, TestShoeboxAppSecureSocialModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.TestMailModule
import com.keepit.search.TestSearchServiceClientModule
import com.keepit.scraper.{ TestScraperServiceClientModule, FakeScrapeSchedulerModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule

class ExtAuthControllerTest extends Specification with ShoeboxTestInjector {

  def requiredModules = Seq(
    TestSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    TestHeimdalServiceClientModule(),
    TestMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    TestScraperServiceClientModule(),
    TestABookServiceClientModule()
  )

  "ExtAuthController" should {
    "start" in {
      withDb(requiredModules: _*) { implicit injector =>
        val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
        val today = now.toDateTime
        inject[FakeClock].push(today)

        val oAuth2Info = OAuth2Info(accessToken = "A",
          tokenType = None, expiresIn = None, refreshToken = None)
        val su = SocialUser(IdentityId("111", "facebook"), "A", "1", "A 1", Some("a1@gmail.com"),
          Some("http://www.fb.com/me"), AuthenticationMethod.OAuth2, None, Some(oAuth2Info), None)
        val user = db.readWrite { implicit s =>
          val user = userRepo.save(User(createdAt = now.minusDays(3), firstName = "A", lastName = "1"))
          val sui = socialUserInfoRepo.save(SocialUserInfo(
            userId = user.id, fullName = "A 1", socialId = SocialId("111"), networkType = FACEBOOK,
            credentials = Some(su)))
          user
        }

        inject[FakeActionAuthenticator].setUser(user)
        //first round
        val fakeRequest1 = FakeRequest().withBody(JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"))))
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
        json1 \ "installationId" === JsString(kifiInstallation1.externalId.id)
        json1 \ "rules" \ "version" must beAnInstanceOf[JsString]
        json1 \ "rules" \ "rules" must beAnInstanceOf[JsObject]
        json1 \ "patterns" must beAnInstanceOf[JsArray]

        //second round
        val fakeRequest2 = FakeRequest()
          .withBody[JsValue](JsObject(Seq("agent" -> JsString("crome agent"), "version" -> JsString("1.1.1"),
            "installation" -> JsString(kifiInstallation1.externalId.id))))
        val authRequest2 = AuthenticatedRequest(null, user.id.get, user, fakeRequest2)
        val result2 = inject[ExtAuthController].start(authRequest2)
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

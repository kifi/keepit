package com.keepit.controllers.website

import org.specs2.mutable.Specification

import net.codingwell.scalaguice.ScalaModule

import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.scraper.FakeScraperModule
import com.keepit.commanders.KeepInfo._
import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders._
import com.keepit.common.db._
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.controller._
import com.keepit.search._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.{FakeHttpClient, HttpClient}
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.social.{SecureSocialUserPlugin, SecureSocialAuthenticatorPlugin, SocialId, SocialNetworks}
import com.keepit.test.ShoeboxApplication

import play.api.libs.json.{JsObject, Json, JsArray, JsString}
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import securesocial.core.providers.Token
import com.keepit.abook.TestABookServiceClientModule

import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.social.{SocialNetworkType, SocialId, SocialNetworks}

class BookmarksControllerTest extends Specification with ApplicationInjector {

  private val creds = SocialUser(IdentityId("asdf", "facebook"),
    "Eishay", "Smith", "Eishay Smith", None, None, AuthenticationMethod.OAuth2, None, None)

  private def setup() = {
    inject[Database].readWrite { implicit session =>
      val userRepo = inject[UserRepo]
      val socialConnectionRepo = inject[SocialConnectionRepo]
      val socialuserInfoRepo = inject[SocialUserInfoRepo]

      val user1 = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
      val user2 = userRepo.save(User(firstName = "Andrew", lastName = "Conner"))

      val su1 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("asdf"),
        networkType = SocialNetworks.FACEBOOK, userId = user1.id, credentials = Some(creds)))
      val su2 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Eishay Smith", socialId = SocialId("aoeu"),
        networkType = SocialNetworks.LINKEDIN, userId = user1.id))
      val su3 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Léo Grimaldi", socialId = SocialId("arst"),
        networkType = SocialNetworks.FACEBOOK, userId = None))
      val su4 = socialuserInfoRepo.save(SocialUserInfo(fullName = "Andrew Conner", socialId = SocialId("abcd"),
        networkType = SocialNetworks.LINKEDIN, userId = user2.id))
      val su5 = socialuserInfoRepo.save(SocialUserInfo(fullName = "杨莹", socialId = SocialId("defg"),
        networkType = SocialNetworks.LINKEDIN, userId = user2.id))


      socialConnectionRepo.save(SocialConnection(socialUser1 = su1.id.get, socialUser2 = su3.id.get))
      socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su4.id.get))
      socialConnectionRepo.save(SocialConnection(socialUser1 = su2.id.get, socialUser2 = su5.id.get))

      (su1, user1)
    }
  }

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScraperModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    TestSliderHistoryTrackerModule(),
    TestHeimdalServiceClientModule()
  )

  def externalIdForTitle(title: String): String = {
    inject[Database].readWrite { implicit session =>
      inject[BookmarkRepo].getByTitle(title).head.externalId.id
    }
  }

  "BookmarksController" should {
    "keepMultiple" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val (su1, user1) = setup()
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", false) ::
          KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", true) ::
          KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", false) ::
          Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.website.routes.BookmarksController.keepMultiple().toString
        path === "/site/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        inject[FakeActionAuthenticator].setUser(user1)
        val controller = inject[BookmarksController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {
            "keeps":[{"id":"${externalIdForTitle("title 11")}","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
                     {"id":"${externalIdForTitle("title 21")}","title":"title 21","url":"http://www.hi.com21","isPrivate":true},
                     {"id":"${externalIdForTitle("title 31")}","title":"title 31","url":"http://www.hi.com31","isPrivate":false}],
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }
  }
}

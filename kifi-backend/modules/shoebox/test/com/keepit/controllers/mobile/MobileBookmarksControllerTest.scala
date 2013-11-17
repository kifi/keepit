package com.keepit.controllers.mobile

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

class MobileBookmarksControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScraperModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule()
  )

  def externalIdForTitle(title: String): String = forTitle(title).externalId.id

  def sourceForTitle(title: String): String = forTitle(title).source.value

  def stateForTitle(title: String): String = forTitle(title).state.value

  def forTitle(title: String): Bookmark = {
    inject[Database].readWrite { implicit session =>
      val bookmarks = inject[BookmarkRepo].getByTitle(title)
      bookmarks.size === 1
      bookmarks.head
    }
  }

  "MobileBookmarksController" should {
    "keepMultiple" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", false) ::
          KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", true) ::
          KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", false) ::
          Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.keepMultiple().toString
        path === "/m/1/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[MobileBookmarksController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        sourceForTitle("title 11") === "MOBILE"
        sourceForTitle("title 21") === "MOBILE"
        sourceForTitle("title 31") === "MOBILE"

        stateForTitle("title 11") === "active"
        stateForTitle("title 21") === "active"
        stateForTitle("title 31") === "active"

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

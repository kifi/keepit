package com.keepit.controllers.ext

import org.specs2.mutable.Specification

import net.codingwell.scalaguice.ScalaModule

import com.keepit.normalizer._
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.commanders.KeepInfo._
import com.keepit.commanders.KeepInfosWithCollection._
import com.keepit.commanders._
import com.keepit.common.db._
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.controller.FortyTwoCookies.{KifiInstallationCookie, ImpersonateCookie}
import com.keepit.common.controller._
import com.keepit.search._
import com.keepit.common.time._
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.net.{FakeHttpClient, HttpClient}
import com.keepit.inject.ApplicationInjector
import com.keepit.model._
import com.keepit.social.{SecureSocialUserPlugin, SecureSocialAuthenticatorPlugin, SocialId, SocialNetworks}
import com.keepit.test.ShoeboxApplication
import scala.concurrent.Await
import scala.concurrent.duration._

import play.api.libs.json.{JsObject, Json, JsArray, JsString}
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.test._
import securesocial.core._
import securesocial.core.providers.Token
import org.joda.time.DateTime

import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import scala.concurrent.ExecutionContext.Implicits.global
import com.keepit.social.{SocialNetworkType, SocialId, SocialNetworks}

class ExtBookmarksControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule()
  )


  "BookmarksController" should {
    "remove tag" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        inject[MidFlightRequests].totalRequestsSoFar === 0L
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val bookmarkRepo = inject[BookmarkRepo]
        val keeper = BookmarkSource.keeper
        val keepToCollectionRepo = inject[KeepToCollectionRepo]
        val db = inject[Database]

        val (user, collections) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
          val normalizationService = inject[NormalizationService]
          val uri1 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val bookmark1 = bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = BookmarkStates.ACTIVE))
          val bookmark2 = bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = BookmarkStates.ACTIVE))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                            Nil
          keepToCollectionRepo.save(KeepToCollection(bookmarkId = bookmark1.id.get, collectionId = collections(0).id.get))
          (user1, collections)
        }

        val bookmarksWithTags = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000)
        }
        bookmarksWithTags.size === 1

        db.readOnly { implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, None, 100).size === 2
          val uris = uriRepo.all
          println(uris mkString "\n")
          uris.size === 2
        }

        val path = com.keepit.controllers.ext.routes.ExtBookmarksController.removeTag(collections(0).externalId).toString
        path === s"/tags/${collections(0).externalId}/removeFromKeep"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withJsonBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        Json.parse(contentAsString(result)) must equalTo(Json.obj())

        val bookmarks = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000)
        }
        bookmarks.size === 0
        inject[MidFlightRequests].totalRequestsSoFar === 1L
      }
    }

    "add tag" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val bookmarkRepo = inject[BookmarkRepo]
        val keeper = BookmarkSource.keeper
        val db = inject[Database]

        val (user, bookmark1, bookmark2, collections) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

          uriRepo.count === 0
          val normalizationService = inject[NormalizationService]
          val uri1 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val bookmark1 = bookmarkRepo.save(Bookmark(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = BookmarkStates.ACTIVE))
          val bookmark2 = bookmarkRepo.save(Bookmark(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = BookmarkStates.ACTIVE))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                            Nil

          (user1, bookmark1, bookmark2, collections)
        }

        db.readOnly {implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, None, 100).size === 2
          val uris = uriRepo.all
          println(uris mkString "\n")
          uris.size === 2
        }

        val path = com.keepit.controllers.ext.routes.ExtBookmarksController.addTag(collections(0).externalId).toString
        path === s"/tags/${collections(0).externalId}/addToKeep"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withJsonBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {"id":"${collections(0).externalId}","name":"myCollaction1"}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)

        db.readWrite {implicit s =>
          val keeps = bookmarkRepo.getByUser(user.id.get, None, None, None, 100)
          println(keeps mkString "\n")
          keeps.size === 2
        }

        val bookmarks = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000)
        }
        bookmarks.size === 1
        bookmarks(0).id.get === bookmark1.id.get
      }
    }


    "add tag and create bookmark if not there" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val bookmarkRepo = inject[BookmarkRepo]
        val keeper = BookmarkSource.keeper
        val db = inject[Database]

        val (user, collections) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

          uriRepo.count === 0
          val normalizationService = inject[NormalizationService]

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                            collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                            Nil

          (user1, collections)
        }

        db.readOnly {implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, None, 100).size === 0
          val uris = uriRepo.all
          uris.size === 0
        }

        val path = com.keepit.controllers.ext.routes.ExtBookmarksController.addTag(collections(0).externalId).toString
        path === s"/tags/${collections(0).externalId}/addToKeep"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withJsonBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.parse(s"""
          {"id":"${collections(0).externalId}","name":"myCollaction1"}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)

        db.readWrite {implicit s =>
          val keeps = bookmarkRepo.getByUser(user.id.get, None, None, None, 100)
          println(keeps mkString "\n")
          keeps.size === 1
        }

        val bookmarks = db.readOnly { implicit s =>
          bookmarkRepo.getByUser(user.id.get, None, None, Some(collections(0).id.get), 1000)
        }
        bookmarks.size === 1
        bookmarks(0).url === "http://www.google.com/"
      }
    }
  }
}

package com.keepit.controllers.ext

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.LibraryCommander
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.curator.FakeCuratorServiceClientModule
import org.specs2.mutable.Specification

import com.keepit.normalizer._
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.common.controller._
import com.keepit.search._
import com.keepit.common.time._
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.test.{ ShoeboxTestInjector, DbInjectionHelper }

import play.api.libs.json.{ JsObject, Json, JsString }
import play.api.test.Helpers._
import play.api.test._
import org.joda.time.DateTime

import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.google.inject.Injector

import com.keepit.cortex.FakeCortexServiceClientModule

class ExtKeepsControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeUserActionsModule(),
    FakeKeepImportsModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  def prenormalize(url: String)(implicit injector: Injector): String = inject[NormalizationService].prenormalize(url).get

  "BookmarksController" should {

    "unkeep" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val libraryRepo = inject[LibraryRepo]
        val keeper = KeepSource.keeper
        val keepToCollectionRepo = inject[KeepToCollectionRepo]
        val db = inject[Database]
        val libCommander = inject[LibraryCommander]
        val extBookmarksController = inject[ExtBookmarksController]

        val (user, k1, collections, lib1) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val k1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection3"))) ::
            Nil
          keepToCollectionRepo.save(KeepToCollection(keepId = k1.id.get, collectionId = collections(0).id.get))
          collectionRepo.collectionChanged(collections(0).id.get, true, false)
          (user1, k1, collections, lib1)
        }

        val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val bookmarksWithTags = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarksWithTags.size === 1

        db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(user.id.get, None, None, 100).size === 2
          val uris = uriRepo.all
          // println(uris mkString "\n") // can be removed?
          uris.size === 2
        }

        val path = routes.ExtBookmarksController.unkeep(k1.externalId).url
        path === s"/ext/keeps/${k1.externalId}/unkeep"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("POST", path)
        val result = extBookmarksController.unkeep(k1.externalId)(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val expected = Json.obj("id" -> k1.externalId, "title" -> "G1", "url" -> "http://www.google.com", "isPrivate" -> false, "libraryId" -> pubLibId1.id)
        Json.parse(contentAsString(result)) must equalTo(expected)

        val bookmarks = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarks.size === 0
      }
    }

    "remove tag" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val libraryRepo = inject[LibraryRepo]
        val keeper = KeepSource.keeper
        val keepToCollectionRepo = inject[KeepToCollectionRepo]
        val db = inject[Database]
        val libCommander = inject[LibraryCommander]
        val extBookmarksController = inject[ExtBookmarksController]

        val (user, collections) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection3"))) ::
            Nil
          keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
          collectionRepo.collectionChanged(collections(0).id.get, true, false)
          (user1, collections)
        }

        val bookmarksWithTags = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarksWithTags.size === 1

        db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(user.id.get, None, None, 100).size === 2
          val uris = uriRepo.all
          // println(uris mkString "\n") // can be removed?
          uris.size === 2
        }

        val path = routes.ExtBookmarksController.removeTag(collections(0).externalId).url
        path === s"/tags/${collections(0).externalId}/removeFromKeep"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = extBookmarksController.removeTag(collections(0).externalId)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json");

        Json.parse(contentAsString(result)) must equalTo(Json.obj())

        val bookmarks = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarks.size === 0
      }
    }

    "add tag" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val libraryRepo = inject[LibraryRepo]
        val keeper = KeepSource.keeper
        val db = inject[Database]
        val libCommander = inject[LibraryCommander]
        val extBookmarksController = inject[ExtBookmarksController]

        val (user, bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection3"))) ::
            Nil

          (user1, bookmark1, bookmark2, collections)
        }

        db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(user.id.get, None, None, 100).size === 2
          val uris = uriRepo.all
          // println(uris mkString "\n") // can be removed?
          uris.size === 2
        }

        val path = routes.ExtBookmarksController.addTag(collections(0).externalId).url
        path === s"/tags/${collections(0).externalId}/addToKeep"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = extBookmarksController.addTag(collections(0).externalId)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.obj("id" -> collections(0).externalId, "name" -> "myCollection1")
        Json.parse(contentAsString(result)) must equalTo(expected)

        db.readWrite { implicit s =>
          val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
          // println(keeps mkString "\n") // can be removed?
          keeps.size === 2
        }

        val bookmarks = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarks.size === 1
        bookmarks(0).id.get === bookmark1.id.get
      }
    }

    "add tag and create bookmark if not there" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val keepRepo = inject[KeepRepo]
        val db = inject[Database]
        val libCommander = inject[LibraryCommander]
        val extBookmarksController = inject[ExtBookmarksController]

        val (user, collections) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          uriRepo.count === 0

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection1"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection2"))) ::
            collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollection3"))) ::
            Nil

          (user1, collections)
        }
        libCommander.internSystemGeneratedLibraries(user.id.get)

        db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(user.id.get, None, None, 100).size === 0
          val uris = uriRepo.all
          uris.size === 0
        }

        val path = routes.ExtBookmarksController.addTag(collections(0).externalId).url
        path === s"/tags/${collections(0).externalId}/addToKeep"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
        val result = extBookmarksController.addTag(collections(0).externalId)(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json")

        val expected = Json.obj("id" -> collections(0).externalId, "name" -> "myCollection1")
        Json.parse(contentAsString(result)) must equalTo(expected)

        db.readWrite { implicit s =>
          val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
          // println(keeps mkString "\n") // can be removed?
          keeps.size === 1
        }

        val bookmarks = db.readOnlyMaster { implicit s =>
          keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
        }
        bookmarks.size === 1
        bookmarks(0).url === "http://www.google.com/"
      }
    }
  }
}

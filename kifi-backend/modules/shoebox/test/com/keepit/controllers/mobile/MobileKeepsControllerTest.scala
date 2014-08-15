package com.keepit.controllers.mobile

import org.specs2.mutable.Specification
import com.keepit.normalizer._
import com.keepit.heimdal.{ KifiHitContext, SanitizedKifiHit, HeimdalContext }
import com.keepit.scraper._
import com.keepit.commanders.KeepInfo._
import com.keepit.commanders._
import com.keepit.common.db._
import com.keepit.common.controller._
import com.keepit.search._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import play.api.libs.json.Json
import play.api.test.Helpers._
import play.api.test._
import com.keepit.common.time._
import org.joda.time.DateTime
import com.google.inject.Injector
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import com.keepit.model.KeepDiscovery
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.model.KeepToCollection
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import play.api.libs.json.JsObject
import com.keepit.model.KifiHitKey
import com.keepit.common.store.FakeShoeboxStoreModule

class MobileKeepsControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    FakeScraperServiceClientModule(),
    FakeCortexServiceClientModule()
  )

  def prenormalize(url: String)(implicit injector: Injector): String = normalizationService.prenormalize(url).get

  "remove tag" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val keeper = KeepSource.keeper

      val (user, bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
          Nil
        keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
        collectionRepo.collectionChanged(collections(0).id.get, true)

        (user1, bookmark1, bookmark2, collections)
      }

      val bookmarksWithTags = db.readOnlyMaster { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
      }
      bookmarksWithTags.size === 1

      db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 2
        val uris = uriRepo.all
        println(uris mkString "\n")
        uris.size === 2
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.removeTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/removeFromKeep"

      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileBookmarksController].removeTag(collections(0).externalId)(request)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

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
      val keeper = KeepSource.keeper

      val (user, bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, isPrivate = false, libraryId = Some(lib1.id.get)))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, isPrivate = false, libraryId = Some(lib1.id.get)))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
          Nil

        (user1, bookmark1, bookmark2, collections)
      }

      db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 2
        val uris = uriRepo.all
        println(uris mkString "\n")
        uris.size === 2
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileBookmarksController].addTag(collections(0).externalId)(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val expected = Json.parse(s"""
        {"id":"${collections(0).externalId}","name":"myCollaction1"}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)

      db.readWrite { implicit s =>
        val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
        println(keeps mkString "\n")
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

      val (user, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
        uriRepo.count === 0

        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
          Nil

        (user1, collections)
      }

      db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 0
        val uris = uriRepo.all
        uris.size === 0
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileBookmarksController].addTag(collections(0).externalId)(request)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
        {"id":"${collections(0).externalId}","name":"myCollaction1"}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)

      db.readWrite { implicit s =>
        val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
        println(keeps mkString "\n")
        keeps.size === 1
      }

      val bookmarks = db.readOnlyMaster { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
      }
      bookmarks.size === 1
      bookmarks(0).url === "http://www.google.com/"
    }
  }

  "allKeeps" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport

      val (user1, user2, bookmark1, bookmark2, bookmark3) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
        val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
        val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))

        (user1, user2, bookmark1, bookmark2, bookmark3)
      }

      val keeps = db.readWrite { implicit s =>
        keepRepo.getByUser(user1.id.get, None, None, 100)
      }
      keeps.size === 2

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allKeeps(before = None, after = None, collection = None, helprank = None).url
      path === "/m/1/keeps/all"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      val sharingUserInfo = Seq(SharingUserInfo(Set(user2.id.get), 3), SharingUserInfo(Set(), 0))
      inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

      inject[FakeActionAuthenticator].setUser(user1)
      val request = FakeRequest("GET", path)
      val result = inject[MobileBookmarksController].allKeeps(
        before = None,
        after = None,
        collectionOpt = None,
        helprankOpt = None,
        count = Integer.MAX_VALUE,
        withPageInfo = false
      )(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val expected = Json.parse(s"""
        {"collection":null,
         "before":null,
         "after":null,
         "keeps":[
          {
            "id":"${bookmark2.externalId.toString}",
            "title":"A1",
            "url":"http://www.amazon.com",
            "isPrivate":false,
            "createdAt":"${bookmark2.createdAt.toStandardTimeString}",
            "others":1,
            "keepers":[{"id":"${user2.externalId.toString}","firstName":"Eishay","lastName":"S","pictureName":"0.jpg"}],
            "collections":[],
            "tags":[],
            "siteName":"Amazon"},
          {
            "id":"${bookmark1.externalId.toString}",
            "title":"G1",
            "url":"http://www.google.com",
            "isPrivate":false,
            "createdAt":"${bookmark1.createdAt.toStandardTimeString}",
            "others":-1,
            "keepers":[],
            "collections":[],
            "tags":[],
            "siteName":"Google"}
        ]}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "allKeeps with after" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport

      val (user, bookmark1, bookmark2, bookmark3) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
        val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))
        val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), state = KeepStates.ACTIVE, libraryId = Some(lib1.id.get)))

        (user1, bookmark1, bookmark2, bookmark3)
      }

      val keeps = db.readWrite { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100)
      }
      keeps.size === 2

      val sharingUserInfo = Seq(SharingUserInfo(Set(), 0), SharingUserInfo(Set(), 0))
      inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

      val request = FakeRequest("GET", s"/m/1/keeps/all?after=${bookmark1.externalId.toString}")
      val result = inject[MobileBookmarksController].allKeeps(
        before = None,
        after = Some(bookmark1.externalId.toString),
        collectionOpt = None,
        helprankOpt = None,
        count = Integer.MAX_VALUE,
        withPageInfo = false
      )(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val expected = Json.parse(s"""
        {
          "collection":null,
          "before":null,
          "after":"${bookmark1.externalId.toString}",
          "keeps":[
            {
              "id":"${bookmark2.externalId.toString}",
              "title":"A1",
              "url":"http://www.amazon.com",
              "isPrivate":false,
              "createdAt":"2013-02-16T23:59:00.000Z",
              "others":-1,
              "keepers":[],
              "collections":[],
              "tags":[],
              "siteName":"Amazon"
            }
          ]
        }
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "saveCollection create mode" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val user = db.readWrite { implicit session =>
        userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.saveCollection().url
      path === "/m/1/collections/create"

      val json = Json.obj("name" -> JsString("my tag"))
      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withJsonBody(json)
      val result = inject[MobileBookmarksController].saveCollection()(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val collection = db.readWrite { implicit session =>
        val collections = inject[CollectionRepo].getUnfortunatelyIncompleteTagSummariesByUser(user.id.get)
        collections.size === 1
        collections.head
      }
      collection.name === "my tag"

      val expected = Json.parse(s"""{"id":"${collection.externalId}","name":"my tag"}""")
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "MobileBookmarksController" should {

    "allCollections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction1")) ::
            collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction2")) ::
            collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction3")) ::
            Nil
          (user, collections)
        }

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allCollections().url
        path === "/m/1/collections/all"

        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[MobileBookmarksController].allCollections(sort = "last_kept")(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val (ext1, ext2, ext3) = db.readOnlyMaster { implicit session =>
          val collections = collectionRepo.all
          collections.length === 3
          (collections(0).externalId, collections(1).externalId, collections(2).externalId)
        }

        val expected = Json.parse(s"""
          {"keeps":0,
           "collections":[
               {"id":"${ext1}","name":"myCollaction1","keeps":0},
               {"id":"${ext2}","name":"myCollaction2","keeps":0},
               {"id":"${ext3}","name":"myCollaction3","keeps":0}
            ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "keepMultiple" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.keepMultiple().url
        path === "/m/1/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withBody(json)
        val result = inject[MobileBookmarksController].keepMultiple()(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val extIds = db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.length === 3
          keeps.map(_.source) === Seq(KeepSource.mobile, KeepSource.mobile, KeepSource.mobile)
          keeps.map(_.state.value) === Seq("active", "active", "active")
          keeps.map(_.externalId)
        }

        val expected = Json.parse(s"""
          {
            "keeps":[{"id":"${extIds(0)}","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
                     {"id":"${extIds(1)}","title":"title 21","url":"http://www.hi.com21","isPrivate":true},
                     {"id":"${extIds(2)}","title":"title 31","url":"http://www.hi.com31","isPrivate":false}],
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "addKeeps" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addKeeps().url
        path === "/m/2/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withBody(json)
        val result = inject[MobileBookmarksController].addKeeps()(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.length === 3
          keeps.map(_.source) === Seq(KeepSource.mobile, KeepSource.mobile, KeepSource.mobile)
          keeps.map(_.state.value) === Seq("active", "active", "active")
        }

        val expected = Json.parse(s"""
          {
            "keepCount":3,
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "unkeepMultiple" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }
        inject[FakeActionAuthenticator].setUser(user)

        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val addPath = com.keepit.controllers.mobile.routes.MobileBookmarksController.addKeeps().url
        addPath === "/m/2/keeps/add"

        val addJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        val addRequest = FakeRequest("POST", addPath).withBody(addJson)
        val addResult = inject[MobileBookmarksController].addKeeps()(addRequest)
        status(addResult) must equalTo(OK);
        contentType(addResult) must beSome("application/json");

        db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.state.value) === Seq("active", "active", "active")
          keeps.map(_.source) === Seq(KeepSource.mobile, KeepSource.mobile, KeepSource.mobile)
        }

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.unkeepMultiple().url
        path === "/m/1/keeps/remove"

        val json = JsArray(withCollection.take(2) map { k => Json.toJson(k) })
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[MobileBookmarksController].unkeepMultiple()(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val (ext1, ext2) = db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.state.value) === Seq("inactive", "inactive", "active")
          (keeps(0).externalId, keeps(1).externalId)
        }

        val expected = Json.parse(s"""
          {"removedKeeps":[
            {"id":"$ext1","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
            {"id":"$ext2","title":"title 21","url":"http://www.hi.com21","isPrivate":true}
          ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "unkeepBatch" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        inject[FakeActionAuthenticator].setUser(user)
        val keepJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.mobile.routes.MobileBookmarksController.keepMultiple().url).withBody(keepJson)
        val keepRes = inject[MobileBookmarksController].keepMultiple()(keepReq)
        status(keepRes) must equalTo(OK)
        contentType(keepRes) must beSome("application/json")

        val keepJsonRes = Json.parse(contentAsString(keepRes))
        val savedKeeps = (keepJsonRes \ "keeps").as[Seq[KeepInfo]]
        savedKeeps.length === withCollection.size
        savedKeeps.forall(k => k.id.nonEmpty) === true

        db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.source) === Seq(KeepSource.mobile, KeepSource.mobile, KeepSource.mobile)
        }

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.unkeepBatch().url
        path === "/m/1/keeps/delete" // remove already taken

        implicit val keepFormat = ExternalId.format[Keep]
        val json = Json.obj("ids" -> JsArray(savedKeeps.take(2) map { k => Json.toJson(k.id.get) }))
        val request = FakeRequest("POST", path).withBody(json)
        val result = inject[MobileBookmarksController].unkeepBatch()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val (ext1, ext2) = db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.state.value) === Seq("inactive", "inactive", "active")
          (keeps(0).externalId, keeps(1).externalId)
        }

        val expected = Json.parse(s"""
          {
            "removedKeeps":[
              {"id":"$ext1","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
              {"id":"$ext2","title":"title 21","url":"http://www.hi.com21","isPrivate":true}
            ],
            "errors":[]
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)

        // todo: add test for error conditions
      }
    }

    "add Keep with Multiple Tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          keepRepo.count === 0
          collectionRepo.all.size === 0
          userRepo.save(User(firstName = "Eishay", lastName = "Smith"))
        }

        val keep1ToCollections = (KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false), Seq("tagA"))
        val keep2ToCollections = (KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = false), Seq("tagA", "tagB", "tagC"))

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addKeepWithTags().url
        path === "/m/1/keeps/addWithTags"

        val json1 = Json.obj(
          "keep" -> keep1ToCollections._1,
          "tagNames" -> keep1ToCollections._2
        )
        inject[FakeActionAuthenticator].setUser(user)
        val request1 = FakeRequest("POST", path).withBody(json1)
        val result1 = inject[MobileBookmarksController].addKeepWithTags()(request1)
        status(result1) must equalTo(OK);
        contentType(result1) must beSome("application/json");

        val tags1 = db.readOnlyMaster { implicit session =>
          keepRepo.count === 1
          collectionRepo.all.size === 1
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user.id.get).map(_.externalId)
        }
        val jsonRes1 = Json.parse(contentAsString(result1))
        val tagSet1 = (jsonRes1 \ "addedToCollections").as[Seq[ExternalId[Collection]]]
        tagSet1.foldLeft(true)((r, c) => r && tags1.contains(c)) === true

        val json2 = Json.obj(
          "keep" -> keep2ToCollections._1,
          "tagNames" -> keep2ToCollections._2
        )
        inject[FakeActionAuthenticator].setUser(user)
        val request2 = FakeRequest("POST", path).withBody(json2)
        val result2 = inject[MobileBookmarksController].addKeepWithTags()(request2)
        status(result2) must equalTo(OK);
        contentType(result2) must beSome("application/json");

        val tags2 = db.readOnlyMaster { implicit session =>
          keepRepo.count === 2
          collectionRepo.all.size === 3
          collectionRepo.getUnfortunatelyIncompleteTagsByUser(user.id.get).map(_.externalId)
        }
        val jsonRes2 = Json.parse(contentAsString(result2))
        val tagSet2 = (jsonRes2 \ "addedToCollections").as[Seq[ExternalId[Collection]]]
        tagSet2.foldLeft(true)((r, c) => r && tags2.contains(c)) === true
      }
    }

  }
}

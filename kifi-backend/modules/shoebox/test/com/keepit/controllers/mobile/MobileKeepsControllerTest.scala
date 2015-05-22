package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal._
import com.keepit.model.{ KeepToCollection, _ }
import com.keepit.search.{ FakeSearchServiceClientModule, _ }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Future

class MobileKeepsControllerTest extends Specification with ShoeboxTestInjector with HelpRankTestHelper {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  import com.keepit.commanders.RawBookmarkRepresentation._

  implicit val helperFormat = RawBookmarkRepresentation.helperFormat

  // NOTE: No attemp to write the trait SourceAttribution
  implicit val rawBookmarkRepwrites = new Writes[RawBookmarkRepresentation] {
    def writes(keep: RawBookmarkRepresentation): JsValue = {
      val tmp = RawBookmarkRepresentationWithoutAttribution(keep.title, keep.url, keep.isPrivate, keep.canonical, keep.openGraph, keep.keptAt, keep.note)
      Json.toJson(tmp)
    }
  }

  def prenormalize(url: String)(implicit injector: Injector): String = normalizationService.prenormalize(url).get

  "remove tag" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val keeper = KeepSource.keeper

      val libCommander = inject[LibraryCommander]

      val (user, bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), keptAt = t1.plusHours(50), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction1"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction2"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction3"))) ::
          Nil
        keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
        collectionRepo.collectionChanged(collections(0).id.get, true, false)

        (user1, bookmark1, bookmark2, collections)
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

      inject[FakeUserActionsHelper].setUser(user)
      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.removeTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/removeFromKeep"

      inject[FakeUserActionsHelper].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileKeepsController].removeTag(collections(0).externalId)(request)
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
      val libCommander = inject[LibraryCommander]

      val (user, bookmark1, bookmark2, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test2"), normalizedUsername = "test2"))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, visibility = lib1.visibility, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, visibility = lib1.visibility, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction1"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction2"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction3"))) ::
          Nil

        (user1, bookmark1, bookmark2, collections)
      }

      db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 2
        val uris = uriRepo.all
        // println(uris mkString "\n") // can be removed?
        uris.size === 2
      }

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.addTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

      inject[FakeUserActionsHelper].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileKeepsController].addTag(collections(0).externalId)(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val expected = Json.parse(s"""
        {"id":"${collections(0).externalId}","name":"myCollaction1"}
      """)
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
      val libCommander = inject[LibraryCommander]

      val (user, collections) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test3"), normalizedUsername = "test3"))
        uriRepo.count === 0

        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction1"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction2"))) ::
          collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("myCollaction3"))) ::
          Nil

        (user1, collections)
      }

      db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 0
        val uris = uriRepo.all
        uris.size === 0
      }

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.addTag(collections(0).externalId).url
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

      inject[FakeUserActionsHelper].setUser(user)
      val request = FakeRequest("POST", path).withBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = inject[MobileKeepsController].addTag(collections(0).externalId)(request)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
        {"id":"${collections(0).externalId}","name":"myCollaction1"}
      """)
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

  "allKeeps" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport

      val (user1, user2, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test1"), normalizedUsername = "test1"))
        val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2, username = Username("test"), normalizedUsername = "test"))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), keptAt = t1.plusHours(50), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), keptAt = t2.plusDays(1), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

        (user1, user2, bookmark1, bookmark2, bookmark3, lib1)
      }
      val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
      val keeps = db.readWrite { implicit s =>
        keepRepo.getByUser(user1.id.get, None, None, 100)
      }
      keeps.size === 2

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allKeepsV1(before = None, after = None, collection = None, helprank = None).url
      path === "/m/1/keeps/all"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      inject[FakeSearchServiceClient].setKeepers((Seq(bookmark1.userId, user2.id.get), 3), (Seq(bookmark2.userId), 1))

      inject[FakeUserActionsHelper].setUser(user1)
      val request = FakeRequest("GET", path)
      val result = inject[MobileKeepsController].allKeepsV1(
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
            "user":{"id":"${user1.externalId}","firstName":"Andrew","lastName":"C","pictureName":"0.jpg","username":"test1"},
            "createdAt":"${bookmark2.keptAt.toStandardTimeString}",
            "keeps":[{"id":"${bookmark2.externalId}", "mine":true, "removable":true, "visibility":"${bookmark2.visibility.value}","libraryId":"${pubLibId1.id}"}],
            "keepers":[{"id":"${user2.externalId.toString}","firstName":"Eishay","lastName":"S","pictureName":"0.jpg", "username":"test"}],
            "keepersOmitted": 0,
            "keepersTotal": 3,
            "libraries":[],
            "librariesOmitted": 0,
            "librariesTotal": 0,
            "collections":[],
            "tags":[],
            "hashtags":[],
            "summary":{},
            "siteName":"Amazon",
            "libraryId":"${pubLibId1.id}"},
          {
            "id":"${bookmark1.externalId.toString}",
            "title":"G1",
            "url":"http://www.google.com",
            "isPrivate":false,
            "user":{"id":"${user1.externalId}","firstName":"Andrew","lastName":"C","pictureName":"0.jpg","username":"test1"},
            "createdAt":"${bookmark1.keptAt.toStandardTimeString}",
            "keeps":[
              {"id":"${bookmark1.externalId}", "mine":true, "removable":true, "visibility":"${bookmark1.visibility.value}", "libraryId":"${pubLibId1.id}"},
              {"id":"${bookmark3.externalId}", "mine":false, "removable":true, "visibility":"${bookmark3.visibility.value}", "libraryId":"${pubLibId1.id}"}
            ],
            "keepers":[],
            "keepersOmitted": 0,
            "keepersTotal": 1,
            "libraries":[],
            "librariesOmitted": 0,
            "librariesTotal": 0,
            "collections":[],
            "tags":[],
            "hashtags":[],
            "summary":{},
            "siteName":"Google",
            "libraryId":"${pubLibId1.id}"}
        ]}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "allKeeps with helprank" in {
    withDb(controllerTestModules: _*) { implicit injector =>

      implicit val context = HeimdalContext.empty
      val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
      val (u1: User, u2: User, _, keeps1: Seq[Keep], _, _) = helpRankSetup(heimdal, db)

      val keeps = db.readOnlyMaster { implicit s =>
        keepRepo.getByUser(u1.id.get, None, None, 100)
      }
      keeps.size === keeps1.size

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allKeepsV1(before = None, after = None, collection = None, helprank = Some("click")).url
      path === "/m/1/keeps/all?helprank=click"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      inject[FakeSearchServiceClient].setKeepers((Seq(keeps1(1).userId, u2.id.get), 3), (Seq(keeps1(0).userId), 1))

      inject[FakeUserActionsHelper].setUser(u1)
      val request = FakeRequest("GET", path)
      val result = inject[MobileKeepsController].allKeepsV1(
        before = None,
        after = None,
        collectionOpt = None,
        helprankOpt = Some("click"),
        count = Integer.MAX_VALUE,
        withPageInfo = false
      )(request)

      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
                  {"collection":null,
                   "before":null,
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(1).externalId.toString}",
                      "url":"${keeps1(1).url}",
                      "isPrivate":${keeps1(1).isPrivate},
                      "user":{"id":"${u1.externalId}","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"},
                      "createdAt":"${keeps1(1).keptAt.toStandardTimeString}",
                      "keeps":[{"id":"${keeps1(1).externalId}", "mine":true, "removable":true, "visibility":"${keeps1(1).visibility.value}", "libraryId":"l7jlKlnA36Su"}],
                      "keepers":[{"id":"${u2.externalId.toString}","firstName":"${u2.firstName}","lastName":"${u2.lastName}","pictureName":"0.jpg","username":"test"}],
                      "keepersOmitted": 0,
                      "keepersTotal": 3,
                      "libraries":[],
                      "librariesOmitted": 0,
                      "librariesTotal": 0,
                      "collections":[],
                      "tags":[],
                      "hashtags":[],
                      "summary":{},
                      "siteName":"Kifi",
                      "libraryId":"l7jlKlnA36Su"
                    },
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "url":"${keeps1(0).url}",
                      "isPrivate":${keeps1(0).isPrivate},
                      "user":{"id":"${u1.externalId}","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"},
                      "createdAt":"${keeps1(0).keptAt.toStandardTimeString}",
                      "keeps":[{"id":"${keeps1(0).externalId}", "mine":true, "removable":true, "visibility":"${keeps1(0).visibility.value}", "libraryId":"l7jlKlnA36Su"}],
                      "keepers":[],
                      "keepersOmitted": 0,
                      "keepersTotal": 1,
                      "libraries":[],
                      "librariesOmitted": 0,
                      "librariesTotal": 0,
                      "collections":[],
                      "tags":[],
                      "hashtags":[],
                      "summary":{},
                      "siteName":"FortyTwo",
                      "libraryId":"l7jlKlnA36Su"
                    }
                  ],
                  "helprank":"click"
                  }
                """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "allKeeps with helprank & before" in {
    withDb(controllerTestModules: _*) { implicit injector =>

      implicit val context = HeimdalContext.empty
      val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]

      val (u1: User, u2: User, _, keeps1: Seq[Keep], _, _) = helpRankSetup(heimdal, db)

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allKeepsV1(before = Some(keeps1(1).externalId.toString), after = None, collection = None, helprank = Some("click")).url
      path === s"/m/1/keeps/all?before=${keeps1(1).externalId.toString}&helprank=click"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      inject[FakeSearchServiceClient].setKeepers((Seq(keeps1(1).userId, u2.id.get), 3))
      inject[FakeUserActionsHelper].setUser(u1)
      val request = FakeRequest("GET", path)
      val result = inject[MobileKeepsController].allKeepsV1(
        before = Some(keeps1(1).externalId.toString),
        after = None,
        collectionOpt = None,
        helprankOpt = Some("click"),
        count = Integer.MAX_VALUE,
        withPageInfo = false
      )(request)
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
                  {"collection":null,
                   "before":"${keeps1(1).externalId.toString}",
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "url":"${keeps1(0).url}",
                      "user":{"id":"${u1.externalId}","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"},
                      "isPrivate":${keeps1(0).isPrivate},
                      "createdAt":"${keeps1(0).keptAt.toStandardTimeString}",
                      "keeps":[{"id":"${keeps1(0).externalId}", "mine":true, "removable":true, "visibility":"${keeps1(0).visibility.value}", "libraryId":"l7jlKlnA36Su"}],
                      "keepers":[{"id":"${u2.externalId.toString}","firstName":"${u2.firstName}","lastName":"${u2.lastName}","pictureName":"0.jpg","username":"test"}],
                      "keepersOmitted": 0,
                      "keepersTotal": 3,
                      "libraries":[],
                      "librariesOmitted": 0,
                      "librariesTotal": 0,
                      "collections":[],
                      "tags":[],
                      "hashtags":[],
                      "summary":{},
                      "siteName":"FortyTwo",
                      "libraryId":"l7jlKlnA36Su"
                    }
                  ],
                  "helprank":"click"
                  }
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

      val (user, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1, username = Username("test1"), normalizedUsername = "test1"))
        val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2, username = Username("test"), normalizedUsername = "test"))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))
        libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), keptAt = t1.plusHours(50), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))
        val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), keptAt = t2.plusDays(1), state = KeepStates.ACTIVE,
          visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

        (user1, bookmark1, bookmark2, bookmark3, lib1)
      }

      val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

      val keeps = db.readWrite { implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100)
      }
      keeps.size === 2

      inject[FakeUserActionsHelper].setUser(user)
      inject[FakeSearchServiceClient].setKeepers((Seq(bookmark1.userId), 1), (Seq(bookmark2.userId), 1))

      val request = FakeRequest("GET", s"/m/1/keeps/all?after=${bookmark1.externalId.toString}")
      val result = inject[MobileKeepsController].allKeepsV1(
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
                "user":{"id":"${user.externalId}","firstName":"Andrew","lastName":"C","pictureName":"0.jpg","username":"test1"},
                "keeps":[{"id":"${bookmark2.externalId}", "mine":true, "removable":true, "visibility":"${bookmark2.visibility.value}","libraryId":"${pubLibId1.id}"}],
                "keepers":[],
                "keepersOmitted": 0,
                "keepersTotal": 1,
                "libraries":[],
                "librariesOmitted": 0,
                "librariesTotal": 0,
                "collections":[],
                "tags":[],
                "hashtags":[],
                "summary":{},
                "siteName":"Amazon",
                "libraryId":"${pubLibId1.id}"
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
        userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileKeepsController.saveCollection().url
      path === "/m/1/collections/create"

      val json = Json.obj("name" -> JsString("my tag"))
      inject[FakeUserActionsHelper].setUser(user)
      val request = FakeRequest("POST", path).withJsonBody(json)
      val result = inject[MobileKeepsController].saveCollection()(request)
      status(result) must equalTo(OK);
      contentType(result) must beSome("application/json");

      val collection = db.readWrite { implicit session =>
        val collections = inject[CollectionRepo].getUnfortunatelyIncompleteTagSummariesByUser(user.id.get)
        collections.size === 1
        collections.head
      }
      collection.name.tag === "my tag"

      val expected = Json.parse(s"""{"id":"${collection.externalId}","name":"my tag"}""")
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "MobileKeepsController" should {

    "allCollections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction1"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction2"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction3"))) ::
            Nil
          (user, collections)
        }

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allCollections().url
        path === "/m/1/collections/all"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[MobileKeepsController].allCollections(sort = "last_kept")(request)
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
          userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)

        val withCollection =
          RawBookmarkRepresentation(title = Some("title 11"), url = "http://www.hi.com11", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 21"), url = "http://www.hi.com21", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 31"), url = "http://www.hi.com31", isPrivate = None) ::
            Nil
        val keepsAndCollections = RawBookmarksWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.keepMultiple().url
        path === "/m/1/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("POST", path).withBody(json)
        val result = inject[MobileKeepsController].keepMultiple()(request)
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
            "keeps":[{"id":"${extIds(0)}","title":"title 11","url":"http://www.hi.com11","isPrivate":false,"libraryId":"l7jlKlnA36Su"},
                     {"id":"${extIds(1)}","title":"title 21","url":"http://www.hi.com21","isPrivate":false,"libraryId":"l7jlKlnA36Su"},
                     {"id":"${extIds(2)}","title":"title 31","url":"http://www.hi.com31","isPrivate":false,"libraryId":"l7jlKlnA36Su"}],
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "unkeepMultiple" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }
        inject[FakeUserActionsHelper].setUser(user)
        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)

        val withCollection =
          RawBookmarkRepresentation(title = Some("title 11"), url = "http://www.hi.com11", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 21"), url = "http://www.hi.com21", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 31"), url = "http://www.hi.com31", isPrivate = None) ::
            Nil
        val keepsAndCollections = RawBookmarksWithCollection(Some(Right("myTag")), withCollection)

        val addPath = com.keepit.controllers.mobile.routes.MobileKeepsController.keepMultiple().url
        addPath === "/m/1/keeps/add"

        val addJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        val addRequest = FakeRequest("POST", addPath).withBody(addJson)
        val addResult = inject[MobileKeepsController].keepMultiple()(addRequest)
        status(addResult) must equalTo(OK);
        contentType(addResult) must beSome("application/json");

        db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.state.value) === Seq("active", "active", "active")
          keeps.map(_.source) === Seq(KeepSource.mobile, KeepSource.mobile, KeepSource.mobile)
        }

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.unkeepMultiple().url
        path === "/m/1/keeps/remove"

        val json = JsArray(withCollection.take(2) map { k => Json.toJson(k) })
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[MobileKeepsController].unkeepMultiple()(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val (ext1, ext2) = db.readOnlyMaster { implicit session =>
          val keeps = keepRepo.all
          keeps.map(_.state.value) === Seq("inactive", "inactive", "active")
          (keeps(0).externalId, keeps(1).externalId)
        }

        val expected = Json.parse(s"""
          {"removedKeeps":[
            {"id":"$ext1","title":"title 11","url":"http://www.hi.com11","isPrivate":false,"libraryId":"l7jlKlnA36Su"},
            {"id":"$ext2","title":"title 21","url":"http://www.hi.com21","isPrivate":false,"libraryId":"l7jlKlnA36Su"}
          ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "add Keep with Selected Tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = db.readWrite { implicit session =>
          keepRepo.count === 0
          collectionRepo.all.size === 0
          userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
        }

        inject[LibraryCommander].internSystemGeneratedLibraries(user.id.get)

        val keep1ToCollections = (Json.obj("title" -> "title 11", "url" -> "http://www.hi.com11", "isPrivate" -> false), Seq("tagA", "tagB", "tagC"))
        val keep2ToCollections = (Json.obj("title" -> "title 11", "url" -> "http://www.hi.com11", "isPrivate" -> false), Seq("tagA", "tagD", "tagE"))
        val keep3ToCollections = (Json.obj("title" -> "title 11", "url" -> "http://www.hi.com11", "isPrivate" -> false), Seq("tagB", "tagD"))

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.addKeepWithTags().url
        path === "/m/1/keeps/addWithTags"

        inject[FakeUserActionsHelper].setUser(user)
        val request1 = FakeRequest("POST", path).withBody(Json.obj("keep" -> keep1ToCollections._1, "tagNames" -> keep1ToCollections._2))
        val result1 = inject[MobileKeepsController].addKeepWithTags()(request1)
        status(result1) must equalTo(OK);
        contentType(result1) must beSome("application/json");

        val keep = db.readOnlyMaster { implicit session =>
          keepRepo.count === 1
          val keep = keepRepo.getByUser(user.id.get).head
          collectionRepo.count(user.id.get) === 3
          keepToCollectionRepo.getByKeep(keep.id.get).size === 3
          keep
        }
        val jsonRes1 = Json.parse(contentAsString(result1)).toString
        jsonRes1.contains("tagA") && jsonRes1.contains("tagB") && jsonRes1.contains("tagC") && !jsonRes1.contains("tagD") === true

        val request2 = FakeRequest("POST", path).withBody(Json.obj("keep" -> keep2ToCollections._1, "tagNames" -> keep2ToCollections._2))
        val result2 = inject[MobileKeepsController].addKeepWithTags()(request2)
        status(result2) must equalTo(OK);
        contentType(result2) must beSome("application/json");

        db.readOnlyMaster { implicit session =>
          collectionRepo.count(user.id.get) === 5
          keepToCollectionRepo.getByKeep(keep.id.get).size === 3
        }
        val jsonRes2 = Json.parse(contentAsString(result2)).toString
        jsonRes2.contains("tagA") && jsonRes2.contains("tagD") && jsonRes2.contains("tagE") && !jsonRes2.contains("tagB") && !jsonRes2.contains("tagC") === true

        val request3 = FakeRequest("POST", path).withBody(Json.obj("keep" -> keep3ToCollections._1, "tagNames" -> keep3ToCollections._2))
        val result3 = inject[MobileKeepsController].addKeepWithTags()(request3)
        status(result3) must equalTo(OK);
        contentType(result3) must beSome("application/json");

        db.readOnlyMaster { implicit session =>
          keepRepo.count === 1
          collectionRepo.count(user.id.get) === 5
          keepToCollectionRepo.getByKeep(keep.id.get).size === 2
        }
        val jsonRes3 = Json.parse(contentAsString(result3)).toString
        jsonRes3.contains("tagB") && jsonRes3.contains("tagD") && !jsonRes3.contains("tagA") && !jsonRes3.contains("tagC") && !jsonRes3.contains("tagE") === true
      }
    }

    "edit note v1" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, keep1, keepWithTags, keepInactive) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val lib = library().withUser(user).saved
          val keep1 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default").saved
          val keep2 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default1").saved
          val keepInactive = KeepFactory.keep().withUser(user).withLibrary(lib).withState(KeepStates.INACTIVE).saved

          val tag1 = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("tag1")))
          val tag2 = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("tag2")))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tag1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tag2.id.get))

          collectionRepo.count(user.id.get) === 2
          keepToCollectionRepo.count === 2

          (user, keep1, keep2, keepInactive)
        }

        def editKeepInfoV1(user: User, keep: Keep, body: JsObject): Future[Result] = {
          inject[FakeUserActionsHelper].setUser(user)
          val path = com.keepit.controllers.mobile.routes.MobileKeepsController.editKeepInfoV1(keep.externalId).url
          val request = FakeRequest("POST", path).withBody(body)
          inject[MobileKeepsController].editKeepInfoV1(keep.externalId)(request)
        }

        val testInactiveKeep = editKeepInfoV1(user, keepInactive, Json.obj("title" -> "blahablhablhahbla"))
        status(testInactiveKeep) must equalTo(NOT_FOUND)

        val testEditTitle = editKeepInfoV1(user, keep1, Json.obj("title" -> ""))
        status(testEditTitle) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === None
        }

        val testEditNote = editKeepInfoV1(user, keep1, Json.obj("note" -> "first comment!"))
        status(testEditNote) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === Some("first comment!")
        }

        val testEditBoth = editKeepInfoV1(user, keep1, Json.obj("title" -> "a real keep", "note" -> "a real note"))
        status(testEditBoth) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
        }

        val testEditNothing = editKeepInfoV1(user, keep1, Json.obj())
        status(testEditNothing) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
          keepToCollectionRepo.count === 2
        }

        val testEditTags1 = editKeepInfoV1(user, keep1, Json.obj("tags" -> Seq("a", "b", "c")))
        status(testEditTags1) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note [#a] [#b] [#c]")
          keepToCollectionRepo.count === 5
          collectionRepo.count(user.id.get) === 5
        }

        val testEditTags2 = editKeepInfoV1(user, keep1, Json.obj("note" -> "a real [#note]", "tags" -> Seq("a", "b", "d", "e")))
        status(testEditTags2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [\\#note] [#a] [#b] [#d] [#e]")
          keepToCollectionRepo.count === 7
          collectionRepo.count(user.id.get) === 7
        }

        val testEditTags3 = editKeepInfoV1(user, keep1, Json.obj("note" -> "a real [#note] thing"))
        status(testEditTags3) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [\\#note] thing [#a] [#b] [#d] [#e]")
        }

        val testEditTags4 = editKeepInfoV1(user, keep1, Json.obj("note" -> "a real [#note] thing", "tags" -> Seq.empty[String]))
        status(testEditTags4) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [\\#note] thing")
        }

        // test a keep that already has tags persisted
        val testEditExistingTags1 = editKeepInfoV1(user, keepWithTags, Json.obj("note" -> "this keep already has [#tags]"))
        status(testEditExistingTags1) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keepWithTags.externalId)
          currentKeep.title === Some("default1")
          currentKeep.note === Some("this keep already has [\\#tags] [#tag1] [#tag2]")
        }
        val testEditExistingTags2 = editKeepInfoV1(user, keepWithTags, Json.obj("note" -> "this keep already has [#tags]", "tags" -> Seq("tag1")))
        status(testEditExistingTags2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keepWithTags.externalId)
          currentKeep.title === Some("default1")
          currentKeep.note === Some("this keep already has [\\#tags] [#tag1]")
        }

      }
    }

    "edit note v2" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, keep1, keepWithTags, keepInactive) = db.readWrite { implicit session =>
          val user = userRepo.save(User(firstName = "Eishay", lastName = "Smith", username = Username("test"), normalizedUsername = "test"))
          val lib = library().withUser(user).saved
          val keep1 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default").saved
          val keep2 = KeepFactory.keep().withUser(user).withLibrary(lib).withTitle("default1").saved
          val keepInactive = KeepFactory.keep().withUser(user).withLibrary(lib).withState(KeepStates.INACTIVE).saved

          collectionRepo.count(user.id.get) === 0
          keepToCollectionRepo.count === 0

          (user, keep1, keep2, keepInactive)
        }

        def editKeepInfoV2(user: User, keep: Keep, body: JsObject): Future[Result] = {
          inject[FakeUserActionsHelper].setUser(user)
          val path = com.keepit.controllers.mobile.routes.MobileKeepsController.editKeepInfoV2(keep.externalId).url
          val request = FakeRequest("POST", path).withBody(body)
          inject[MobileKeepsController].editKeepInfoV2(keep.externalId)(request)
        }

        val testInactiveKeep = editKeepInfoV2(user, keepInactive, Json.obj("title" -> "blahablhablhahbla"))
        status(testInactiveKeep) must equalTo(NOT_FOUND)

        val testEditTitle = editKeepInfoV2(user, keep1, Json.obj("title" -> ""))
        status(testEditTitle) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === None
        }

        val testEditNote = editKeepInfoV2(user, keep1, Json.obj("note" -> "first comment!"))
        status(testEditNote) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === None
          currentKeep.note === Some("first comment!")
        }

        val testEditBoth = editKeepInfoV2(user, keep1, Json.obj("title" -> "a real keep", "note" -> "a real note"))
        status(testEditBoth) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
        }

        val testEditNothing = editKeepInfoV2(user, keep1, Json.obj())
        status(testEditNothing) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real note")
          keepToCollectionRepo.count === 0
        }

        val testEditWithHashtags = editKeepInfoV2(user, keep1, Json.obj("note" -> "a real [#note]"))
        status(testEditWithHashtags) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [#note]")
          collectionRepo.getHashtagsByKeepId(currentKeep.id.get).map(_.tag) === Set("note")
        }

        val testEditWithHashtags2 = editKeepInfoV2(user, keep1, Json.obj("note" -> "a real [#note]. #Finally! [#woo[hoo\\]]"))
        status(testEditWithHashtags2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val currentKeep = keepRepo.get(keep1.externalId)
          currentKeep.title === Some("a real keep")
          currentKeep.note === Some("a real [#note]. #Finally! [#woo[hoo\\]]")
          collectionRepo.getHashtagsByKeepId(currentKeep.id.get).map(_.tag) === Set("note", "woo[hoo]")
        }
      }
    }
  }

}

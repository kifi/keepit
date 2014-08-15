package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.common.db.slick.Database
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext, KifiHitContext, SanitizedKifiHit }
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsArray, JsString, Json }
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Await
import scala.concurrent.duration._

class KeepsControllerTest extends Specification with ShoeboxTestInjector {

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

  def externalIdForTitle(title: String)(implicit injector: Injector): String = forTitle(title).externalId.id
  def externalIdForCollection(userId: Id[User], name: String)(implicit injector: Injector): String = forCollection(userId, name).externalId.id

  def sourceForTitle(title: String)(implicit injector: Injector): KeepSource = forTitle(title).source

  def stateForTitle(title: String)(implicit injector: Injector): String = forTitle(title).state.value

  def forTitle(title: String)(implicit injector: Injector): Keep = {
    inject[Database].readWrite { implicit session =>
      val keeps = inject[KeepRepo].getByTitle(title)
      keeps.size === 1
      keeps.head
    }
  }

  def forCollection(userId: Id[User], name: String)(implicit injector: Injector): Collection = {
    inject[Database].readWrite { implicit session =>
      val collections = inject[CollectionRepo].getByUserAndName(userId, name)
      collections.size === 1
      collections.head
    }
  }

  "KeepsController" should {

    "allKeeps" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val libraryRepo = inject[LibraryRepo]
        val keeper = KeepSource.keeper
        val initLoad = KeepSource.bookmarkImport
        val db = inject[Database]

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

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = None, after = None, collection = None, helprank = None).toString
        path === "/site/keeps/all"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
        val sharingUserInfo = Seq(SharingUserInfo(Set(user2.id.get), 3), SharingUserInfo(Set(), 0))
        inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

        val controller = inject[KeepsController]
        inject[FakeActionAuthenticator].setUser(user1)

        Await.result(inject[FakeSearchServiceClient].sharingUserInfo(null, Seq()), Duration(1, SECONDS)) === sharingUserInfo
        val request = FakeRequest()
        val result = inject[KeepsController].allKeeps(before = None, after = None, collectionOpt = None, helprankOpt = None, count = 20, withPageInfo = false)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          {"collection":null,
           "before":null,
           "after":null,
           "keeps":[
            {
              "id":"${bookmark2.externalId.toString}",
              "title":"A1",
              "url":"http://www.amazon.com/",
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
              "url":"http://www.google.com/",
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

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val libraryRepo = inject[LibraryRepo]
        val keeper = KeepSource.keeper
        val initLoad = KeepSource.bookmarkImport
        val db = inject[Database]

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

        val request = FakeRequest("GET", s"/site/keeps/all?after=${bookmark1.externalId.toString}")
        val result = inject[KeepsController].allKeeps(before = None, after = Some(bookmark1.externalId.toString), collectionOpt = None, helprankOpt = None, count = 20, withPageInfo = false)(request)
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
                "url":"http://www.amazon.com/",
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

    "allCollections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
          val collections = inject[CollectionRepo].save(Collection(userId = user.id.get, name = "myCollaction1")) ::
            inject[CollectionRepo].save(Collection(userId = user.id.get, name = "myCollaction2")) ::
            inject[CollectionRepo].save(Collection(userId = user.id.get, name = "myCollaction3")) ::
            Nil
          (user, collections)
        }

        val path = com.keepit.controllers.website.routes.KeepsController.allCollections().toString
        path === "/site/collections/all"

        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("GET", path)
        val result = controller.allCollections("last_kept")(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
          {"keeps":0,
           "collections":[
              {"id":"${externalIdForCollection(user.id.get, "myCollaction1")}","name":"myCollaction1","keeps":0},
              {"id":"${externalIdForCollection(user.id.get, "myCollaction2")}","name":"myCollaction2","keeps":0},
              {"id":"${externalIdForCollection(user.id.get, "myCollaction3")}","name":"myCollaction3","keeps":0}
            ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "keepMultiple" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString
        path === "/site/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withBody(json)
        val result = controller.keepMultiple()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        sourceForTitle("title 11") === KeepSource.site
        sourceForTitle("title 21") === KeepSource.site
        sourceForTitle("title 31") === KeepSource.site

        stateForTitle("title 11") === "active"
        stateForTitle("title 21") === "active"
        stateForTitle("title 31") === "active"

        val expected = Json.parse(s"""
          {
            "keeps":[{"id":"${externalIdForTitle("title 11")}","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
                     {"id":"${externalIdForTitle("title 21")}","title":"title 21","url":"http://www.hi.com21","isPrivate":true},
                     {"id":"${externalIdForTitle("title 31")}","title":"title 31","url":"http://www.hi.com31","isPrivate":false}],
            "failures":[],
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "saveCollection create mode" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection("").toString
        path === "/site/collections/create"

        val json = Json.obj("name" -> JsString("my tag"))
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[KeepsController].saveCollection("")(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val collection = inject[Database].readWrite { implicit session =>
          val collections = inject[CollectionRepo].getUnfortunatelyIncompleteTagSummariesByUser(user.id.get)
          collections.size === 1
          collections.head
        }
        collection.name === "my tag"

        val expected = Json.parse(s"""
          {"id":"${collection.externalId}","name":"my tag"}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "saveCollection create mode with long name" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection("").toString

        val json = Json.obj("name" -> JsString("my tag is very very very very very very very very very very very very very very very very very long"))
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[KeepsController].saveCollection("")(request)
        status(result) must equalTo(400)
      }
    }

    "unkeepBatch" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
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
        val keepReq = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString).withBody(keepJson)
        val keepRes = inject[KeepsController].keepMultiple()(keepReq)
        status(keepRes) must equalTo(OK)
        contentType(keepRes) must beSome("application/json")
        val keepJsonRes = Json.parse(contentAsString(keepRes))
        val savedKeeps = (keepJsonRes \ "keeps").as[Seq[KeepInfo]]
        savedKeeps.length === withCollection.size
        savedKeeps.forall(k => k.id.nonEmpty) === true

        sourceForTitle("title 11") === KeepSource.site
        sourceForTitle("title 21") === KeepSource.site
        sourceForTitle("title 31") === KeepSource.site

        val path = com.keepit.controllers.website.routes.KeepsController.unkeepBatch().toString
        path === "/site/keeps/delete" // remove already taken

        implicit val keepFormat = ExternalId.format[Keep]
        val json = Json.obj("ids" -> JsArray(savedKeeps.take(2) map { k => Json.toJson(k.id.get) }))
        val request = FakeRequest("POST", path).withBody(json)

        val result = inject[KeepsController].unkeepBatch()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        stateForTitle("title 31") === "active"
        stateForTitle("title 11") === "inactive"
        stateForTitle("title 21") === "inactive"

        val expected = Json.parse(s"""
          {
            "removedKeeps":[
              {"id":"${externalIdForTitle("title 11")}","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
              {"id":"${externalIdForTitle("title 21")}","title":"title 21","url":"http://www.hi.com21","isPrivate":true}
            ],
            "errors":[]
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)

        // todo: add test for error conditions
      }
    }

    "unkeepMultiple" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
            KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
            KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
            Nil

        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val keepJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map { k => Json.toJson(k) })
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString).withBody(keepJson)
        val keepRes = inject[KeepsController].keepMultiple()(keepReq)
        status(keepRes) must equalTo(OK);
        contentType(keepRes) must beSome("application/json");

        sourceForTitle("title 11") === KeepSource.site
        sourceForTitle("title 21") === KeepSource.site
        sourceForTitle("title 31") === KeepSource.site

        val path = com.keepit.controllers.website.routes.KeepsController.unkeepMultiple().toString
        path === "/site/keeps/remove"

        val json = JsArray(withCollection.take(2) map { k => Json.toJson(k) })
        val request = FakeRequest("POST", path).withJsonBody(json)

        val result = inject[KeepsController].unkeepMultiple()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        stateForTitle("title 31") === "active"

        stateForTitle("title 11") === "inactive"
        stateForTitle("title 21") === "inactive"

        val expected = Json.parse(s"""
          {"removedKeeps":[
            {"id":"${externalIdForTitle("title 11")}","title":"title 11","url":"http://www.hi.com11","isPrivate":false},
            {"id":"${externalIdForTitle("title 21")}","title":"title 21","url":"http://www.hi.com21","isPrivate":true}
          ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "reorder tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, oldOrdering, tagA, tagB, tagC, tagD) = inject[Database].readWrite { implicit session =>
          val user1 = inject[UserRepo].save(User(firstName = "Tony", lastName = "Stark"))

          val tagA = Collection(userId = user1.id.get, name = "tagA")
          val tagB = Collection(userId = user1.id.get, name = "tagB")
          val tagC = Collection(userId = user1.id.get, name = "tagC")
          val tagD = Collection(userId = user1.id.get, name = "tagD")

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(tagA) ::
            collectionRepo.save(tagB) ::
            collectionRepo.save(tagC) ::
            collectionRepo.save(tagD) ::
            Nil

          val collectionIds = collections.map(_.externalId).toSeq
          inject[UserValueRepo].save(UserValue(userId = user1.id.get, name = UserValueName.USER_COLLECTION_ORDERING, value = Json.stringify(Json.toJson(collectionIds))))
          (user1, collectionIds, tagA, tagB, tagC, tagD)
        }

        inject[FakeActionAuthenticator].setUser(user)

        val inputJson1 = Json.obj(
          "tagId" -> tagA.externalId,
          "newIndex" -> 2
        )
        val request1 = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.
          updateCollectionIndexOrdering().toString).withBody(inputJson1)

        val inputJson2 = Json.obj(
          "tagId" -> tagD.externalId,
          "newIndex" -> 0
        )
        val request2 = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.
          updateCollectionIndexOrdering().toString).withBody(inputJson2)

        val inputJson3 = Json.obj(
          "tagId" -> tagB.externalId,
          "newIndex" -> 3
        )
        val request3 = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.
          updateCollectionIndexOrdering().toString).withBody(inputJson3)

        val result1 = inject[KeepsController].updateCollectionIndexOrdering()(request1)
        val result2 = inject[KeepsController].updateCollectionIndexOrdering()(request2)
        val result3 = inject[KeepsController].updateCollectionIndexOrdering()(request3)

        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""{"newCollection":[
             |"${tagB.externalId}",
             |"${tagC.externalId}",
             |"${tagA.externalId}",
             |"${tagD.externalId}"]}
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)

        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val expected2 = Json.parse(
          s"""{"newCollection":[
             |"${tagD.externalId}",
             |"${tagB.externalId}",
             |"${tagC.externalId}",
             |"${tagA.externalId}"]}
           """.stripMargin)
        Json.parse(contentAsString(result2)) must equalTo(expected2)

        status(result3) must equalTo(OK);
        contentType(result3) must beSome("application/json");

        val expected3 = Json.parse(
          s"""{"newCollection":[
             |"${tagD.externalId}",
             |"${tagC.externalId}",
             |"${tagA.externalId}",
             |"${tagB.externalId}"]}
           """.stripMargin)
        Json.parse(contentAsString(result3)) must equalTo(expected3)
      }
    }
  }
}

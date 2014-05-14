package com.keepit.controllers.website

import org.specs2.mutable.Specification

import net.codingwell.scalaguice.ScalaModule

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
import com.keepit.common.external.FakeExternalServiceModule

class KeepsControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule(),
    FakeExternalServiceModule()
  )

  def externalIdForTitle(title: String): String = forTitle(title).externalId.id
  def externalIdForCollection(userId: Id[User], name: String): String = forCollection(userId, name).externalId.id

  def sourceForTitle(title: String): KeepSource = forTitle(title).source

  def stateForTitle(title: String): String = forTitle(title).state.value

  def forTitle(title: String): Keep = {
    inject[Database].readWrite { implicit session =>
      val keeps = inject[KeepRepo].getByTitle(title)
      keeps.size === 1
      keeps.head
    }
  }

  def forCollection(userId: Id[User], name: String): Collection = {
    inject[Database].readWrite { implicit session =>
      val collections = inject[CollectionRepo].getByUserAndName(userId, name)
      collections.size === 1
      collections.head
    }
  }

  "KeepsController" should {
    "allKeeps" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val keeper = KeepSource.keeper
        val initLoad = KeepSource.bookmarkImport
        val db = inject[Database]

        val (user1, user2, bookmark1, bookmark2, bookmark3) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
          val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE))
          val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), state = KeepStates.ACTIVE))

          (user1, user2, bookmark1, bookmark2, bookmark3)
        }

        val keeps = db.readWrite {implicit s =>
          keepRepo.getByUser(user1.id.get, None, None, 100)
        }
        keeps.size === 2

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = None, after = None, collection = None).toString
        path === "/site/keeps/all"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
        val sharingUserInfo = Seq(SharingUserInfo(Set(user2.id.get), 3), SharingUserInfo(Set(), 0))
        inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

        val controller = inject[KeepsController]
        inject[FakeActionAuthenticator].setUser(user1)

        import play.api.Play.current
        println("global id: " + current.global.asInstanceOf[com.keepit.FortyTwoGlobal].globalId)

        Await.result(inject[FakeSearchServiceClient].sharingUserInfo(null, Seq()), Duration(1, SECONDS)) === sharingUserInfo
        val request = FakeRequest("GET", path)
        val result = route(request).get
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
              "url":"http://www.amazon.com/",
              "isPrivate":false,
              "createdAt":"${bookmark2.createdAt.toStandardTimeString}",
              "others":1,
              "keepers":[{"id":"${user2.externalId.toString}","firstName":"Eishay","lastName":"S","pictureName":"0.jpg"}],
              "clickCount":0,
              "collections":[]},
            {
              "id":"${bookmark1.externalId.toString}",
              "title":"G1",
              "url":"http://www.google.com/",
              "isPrivate":false,
              "createdAt":"${bookmark1.createdAt.toStandardTimeString}",
              "others":-1,
              "keepers":[],
              "clickCount":0,
              "collections":[]}
          ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "allKeeps with after" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val urlRepo = inject[URLRepo]
        val keepRepo = inject[KeepRepo]
        val keeper = KeepSource.keeper
        val initLoad = KeepSource.bookmarkImport
        val db = inject[Database]

        val (user, bookmark1, bookmark2, bookmark3) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
          val user2 = userRepo.save(User(firstName = "Eishay", lastName = "S", createdAt = t2))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE))
          val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), state = KeepStates.ACTIVE))

          (user1, bookmark1, bookmark2, bookmark3)
        }

        val keeps = db.readWrite {implicit s =>
          keepRepo.getByUser(user.id.get, None, None, 100)
        }
        keeps.size === 2

        val sharingUserInfo = Seq(SharingUserInfo(Set(), 0), SharingUserInfo(Set(), 0))
        inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

        val request = FakeRequest("GET", s"/site/keeps/all?after=${bookmark1.externalId.toString}")
        val result = route(request).get
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
                "clickCount":0,
                "collections":[]
              }
            ]
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "allCollections" in {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
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
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

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

        val path = com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString
        path === "/site/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

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
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "saveCollection create mode" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection("").toString
        path === "/site/collections/create"

        val json = Json.obj("name" -> JsString("my tag"))
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
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

    "saveCollection create mode with long name" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection("").toString

        val json = Json.obj("name" -> JsString("my tag is very very very very very very very very very very very very very very very very very long"))
        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
        status(result) must equalTo(400);
      }
    }

    "unkeepBatch" in  {
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

        inject[FakeActionAuthenticator].setUser(user)
        val keepJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString).withJsonBody(keepJson)
        val keepRes = route(keepReq).get
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
        val json = Json.obj("ids" -> JsArray(savedKeeps.take(2) map {k => Json.toJson(k.id.get)}))
        val request = FakeRequest("POST", path).withJsonBody(json)

        val result = route(request).get
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

    "unkeepMultiple" in  {
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

        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[KeepsController]
        val keepJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.website.routes.KeepsController.keepMultiple().toString).withJsonBody(keepJson)
        val keepRes = route(keepReq).get
        status(keepRes) must equalTo(OK);
        contentType(keepRes) must beSome("application/json");

        sourceForTitle("title 11") === KeepSource.site
        sourceForTitle("title 21") === KeepSource.site
        sourceForTitle("title 31") === KeepSource.site

        val path = com.keepit.controllers.website.routes.KeepsController.unkeepMultiple().toString
        path === "/site/keeps/remove"

        val json = JsArray(withCollection.take(2) map {k => Json.toJson(k)})
        val request = FakeRequest("POST", path).withJsonBody(json)

        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

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
  }
}

package com.keepit.controllers.mobile

import org.specs2.mutable.Specification
import net.codingwell.scalaguice.ScalaModule
import com.keepit.normalizer._
import com.keepit.heimdal.{KifiHitContext, SanitizedKifiHit, HeimdalContext, TestHeimdalServiceClientModule}
import com.keepit.scraper.FakeScrapeSchedulerModule
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
import com.keepit.common.time._
import org.joda.time.DateTime
import com.google.inject.Injector
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.controllers.website.KeepsController
import scala.concurrent.Await
import scala.concurrent.duration._
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import scala.Some
import com.keepit.model.KeepDiscovery
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.model.KeepToCollection
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.heimdal.TestHeimdalServiceClientModule
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.scraper.TestScraperServiceClientModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.search.FakeSearchServiceClientModule
import play.api.libs.json.JsObject
import com.keepit.model.KifiHitKey
import com.keepit.common.store.ShoeboxFakeStoreModule


class MobileKeepsControllerTest extends Specification with ApplicationInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    TestHeimdalServiceClientModule(),
    FakeExternalServiceModule(),
    TestScraperServiceClientModule(),
    FakeCortexServiceClientModule()
  )

  def externalIdForTitle(title: String): String = forTitle(title).externalId.id
  def externalIdForCollection(userId: Id[User], name: String): String = forCollection(userId, name).externalId.id

  def sourceForTitle(title: String): KeepSource = forTitle(title).source

  def stateForTitle(title: String): String = forTitle(title).state.value

  def forTitle(title: String): Keep = {
    inject[Database].readWrite { implicit session =>
      val bookmarks = inject[KeepRepo].getByTitle(title)
      bookmarks.size === 1
      bookmarks.head
    }
  }

  def forCollection(userId: Id[User], name: String): Collection = {
    inject[Database].readWrite { implicit session =>
      val collections = inject[CollectionRepo].getByUserAndName(userId, name)
      collections.size === 1
      collections.head
    }
  }

  def prenormalize(url: String)(implicit injector: Injector): String = inject[NormalizationService].prenormalize(url).get

  "remove tag" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

      val userRepo = inject[UserRepo]
      val uriRepo = inject[NormalizedURIRepo]
      val urlRepo = inject[URLRepo]
      val keepRepo = inject[KeepRepo]
      val keeper = KeepSource.keeper
      val keepToCollectionRepo = inject[KeepToCollectionRepo]
      val db = inject[Database]

      val (user, bookmark1, bookmark2, collections) = db.readWrite {implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                          Nil
        keepToCollectionRepo.save(KeepToCollection(keepId = bookmark1.id.get, collectionId = collections(0).id.get))
        collectionRepo.collectionChanged(collections(0).id.get, true)

        (user1, bookmark1, bookmark2, collections)
      }

      val bookmarksWithTags = db.readOnly { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
      }
      bookmarksWithTags.size === 1

      db.readOnly {implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 2
        val uris = uriRepo.all
        println(uris mkString "\n")
        uris.size === 2
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.removeTag(collections(0).externalId).toString
      path === s"/m/1/tags/${collections(0).externalId}/removeFromKeep"

      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withJsonBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = route(request).get
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      Json.parse(contentAsString(result)) must equalTo(Json.obj())

      val bookmarks = db.readOnly { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
      }
      bookmarks.size === 0
    }
  }

  "add tag" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

      val userRepo = inject[UserRepo]
      val uriRepo = inject[NormalizedURIRepo]
      val urlRepo = inject[URLRepo]
      val keepRepo = inject[KeepRepo]
      val keeper = KeepSource.keeper
      val db = inject[Database]

      val (user, bookmark1, bookmark2, collections) = db.readWrite {implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

        uriRepo.count === 0
        val uri1 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.google.com/"), Some("Google")))
        val uri2 = uriRepo.save(NormalizedURI.withHash(prenormalize("http://www.amazon.com/"), Some("Amazon")))

        val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
        val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

        val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
          uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE, isPrivate = false))
        val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
          uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE, isPrivate = false))

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                          Nil

        (user1, bookmark1, bookmark2, collections)
      }

      db.readOnly {implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 2
        val uris = uriRepo.all
        println(uris mkString "\n")
        uris.size === 2
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addTag(collections(0).externalId).toString
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

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
        val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
        println(keeps mkString "\n")
        keeps.size === 2
      }

      val bookmarks = db.readOnly { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
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
      val keepRepo = inject[KeepRepo]
      val db = inject[Database]

      val (user, collections) = db.readWrite {implicit s =>
        val user1 = userRepo.save(User(firstName = "Andrew", lastName = "C", createdAt = t1))

        uriRepo.count === 0

        val collectionRepo = inject[CollectionRepo]
        val collections = collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction1")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction2")) ::
                          collectionRepo.save(Collection(userId = user1.id.get, name = "myCollaction3")) ::
                          Nil

        (user1, collections)
      }

      db.readOnly {implicit s =>
        keepRepo.getByUser(user.id.get, None, None, 100).size === 0
        val uris = uriRepo.all
        uris.size === 0
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addTag(collections(0).externalId).toString
      path === s"/m/1/tags/${collections(0).externalId}/addToKeep"

      inject[FakeActionAuthenticator].setUser(user)
      val request = FakeRequest("POST", path).withJsonBody(JsObject(Seq("url" -> JsString("http://www.google.com/"))))
      val result = route(request).get
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
        {"id":"${collections(0).externalId}","name":"myCollaction1"}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)

      db.readWrite {implicit s =>
        val keeps = keepRepo.getByUser(user.id.get, None, None, 100)
        println(keeps mkString "\n")
        keeps.size === 1
      }

      val bookmarks = db.readOnly { implicit s =>
        keepRepo.getByUserAndCollection(user.id.get, collections(0).id.get, None, None, 1000)
      }
      bookmarks.size === 1
      bookmarks(0).url === "http://www.google.com/"
    }
  }

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

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allKeeps(before = None, after = None, collection = None, helprank = None).toString
      path === "/m/1/keeps/all"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      val sharingUserInfo = Seq(SharingUserInfo(Set(user2.id.get), 3), SharingUserInfo(Set(), 0))
      inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

      val controller = inject[MobileBookmarksController]
      inject[FakeActionAuthenticator].setUser(user1)

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

  "allKeeps with helprank" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {

      val keep42   = Json.obj("url" -> "http://42go.com", "isPrivate" -> false)
      val keepKifi = Json.obj("url" -> "http://kifi.com", "isPrivate" -> false)
      val keepGoog = Json.obj("url" -> "http://google.com", "isPrivate" -> false)
      val keepBing = Json.obj("url" -> "http://bing.com", "isPrivate" -> false)
      val keepStanford = Json.obj("url" -> "http://stanford.edu", "isPrivate" -> false)
      val keepApple = Json.obj("url" -> "http://apple.com", "isPrivate" -> false)

      implicit val context = HeimdalContext.empty
      val userRepo = inject[UserRepo]
      val uriRepo = inject[NormalizedURIRepo]
      val urlRepo = inject[URLRepo]
      val keepRepo = inject[KeepRepo]
      val keepDiscoveryRepo = inject[KeepDiscoveryRepo]
      val rekeepRepo = inject[ReKeepRepo]
      val userExpRepo = inject[UserExperimentRepo]
      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport
      val db = inject[Database]

      val (u1, u2, u3, u4) = db.readWrite { implicit session =>
        val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        val u2 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
        val u3 = userRepo.save(User(firstName = "Discoveryer", lastName = "DiscoveryetyDiscoveryyDiscovery"))
        val u4 = userRepo.save(User(firstName = "Ro", lastName = "Bot"))

        (u1, u2, u3, u4)
      }
      val bookmarkInterner = inject[KeepInterner]
      val raw1 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keep42, keepKifi))
      val raw2 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepBing))
      val raw3 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepStanford))
      val raw4 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepApple))

      val (keeps1, _) = bookmarkInterner.internRawBookmarks(raw1, u1.id.get, KeepSource.email, true)
      val (keeps2, _) = bookmarkInterner.internRawBookmarks(raw2, u2.id.get, KeepSource.default, true)
      keeps1.size === 2
      keeps2.size === 3
      keeps1(1).uriId === keeps2(0).uriId

      val (kc0, kc1, kc2) = db.readWrite { implicit rw =>
        val kifiHitCache = inject[KifiHitCache]
        val origin = "https://www.google.com"
        val kc0 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId))
        // u2 -> 42 (u1)
        kifiHitCache.set(KifiHitKey(u2.id.get, keeps1(0).uriId), SanitizedKifiHit(kc0.hitUUID, origin, raw1(0).url, kc0.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId), Seq.empty, None, 0, 0)))

        val ts = currentDateTime
        val uuid = ExternalId[SanitizedKifiHit]()
        val kc1 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId))
        val kc2 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId))
        // u3 -> kifi (u1, u2) [rekeep]
        kifiHitCache.set(KifiHitKey(u3.id.get, keeps1(1).uriId), SanitizedKifiHit(kc1.hitUUID, origin, raw1(1).url, kc1.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId, u2.externalId), Seq.empty, None, 0, 0)))

        (kc0, kc1, kc2)
      }

      val (keeps3, _) = bookmarkInterner.internRawBookmarks(raw3, u3.id.get, KeepSource.default, true)

      val kc3 = db.readWrite { implicit rw =>
        val kifiHitCache = inject[KifiHitCache]
        val origin = "https://www.google.com"
        val kc3 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId))
        // u4 -> kifi (u3) [rekeep]
        kifiHitCache.set(KifiHitKey(u4.id.get, keeps3(0).uriId), SanitizedKifiHit(kc3.hitUUID, origin, raw3(0).url, kc3.uriId, KifiHitContext(false, false, 0, Seq(u3.externalId), Seq.empty, None, 0, 0)))
        kc3
      }

      val (keeps4, _) = bookmarkInterner.internRawBookmarks(raw4, u4.id.get, KeepSource.default, true)

      val (keeps, clickCount, rekeepCount, clicks, rekeeps) = db.readOnly {implicit s =>
        val keeps = keepRepo.getByUser(u1.id.get, None, None, 100)
        val clickCount = keepDiscoveryRepo.getDiscoveryCountByKeeper(u1.id.get)
        val clicks = keepDiscoveryRepo.getDiscoveryCountsByKeeper(u1.id.get)
        val rekeepCount = rekeepRepo.getReKeepCountByKeeper(u1.id.get)
        val rekeeps = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
        (keeps, clickCount, rekeepCount, clicks, rekeeps)
      }
      keeps.size === keeps1.size
      clickCount === 2
      rekeepCount === 1
      clicks.keySet.size === 2
      rekeeps.keySet.size === 1

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allKeeps(before = None, after = None, collection = None, helprank = Some("click")).toString
      path === "/m/1/keeps/all?helprank=click"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      val sharingUserInfo = Seq(SharingUserInfo(Set(u2.id.get), 3), SharingUserInfo(Set(), 0))
      inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

      val controller = inject[KeepsController]
      inject[FakeActionAuthenticator].setUser(u1)

      import play.api.Play.current
      println("global id: " + current.global.asInstanceOf[com.keepit.FortyTwoGlobal].globalId)

      Await.result(inject[FakeSearchServiceClient].sharingUserInfo(null, Seq()), Duration(1, SECONDS)) === sharingUserInfo
      val request = FakeRequest("GET", path)
      val result = route(request).get
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
                  {"collection":null,
                   "before":null,
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "url":"${keeps1(0).url}",
                      "isPrivate":${keeps1(0).isPrivate},
                      "createdAt":"${keeps1(0).createdAt.toStandardTimeString}",
                      "others":1,
                      "keepers":[{"id":"${u2.externalId.toString}","firstName":"${u2.firstName}","lastName":"${u2.lastName}","pictureName":"0.jpg"}],
                      "collections":[],
                      "tags":[],
                      "siteName":"FortyTwo",
                      "clickCount":1
                    },
                    {
                      "id":"${keeps1(1).externalId.toString}",
                      "url":"${keeps1(1).url}",
                      "isPrivate":${keeps1(1).isPrivate},
                      "createdAt":"${keeps1(1).createdAt.toStandardTimeString}",
                      "others":-1,
                      "keepers":[],
                      "clickCount":1,
                      "collections":[],
                      "tags":[],
                      "siteName":"kifi.com",
                      "clickCount":1,
                      "rekeepCount":1
                    }
                  ],
                  "helprank":"click"
                  }
                """)

      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "allKeeps with helprank & before" in {
    running(new ShoeboxApplication(controllerTestModules:_*)) {

      val keep42   = Json.obj("url" -> "http://42go.com", "isPrivate" -> false)
      val keepKifi = Json.obj("url" -> "http://kifi.com", "isPrivate" -> false)
      val keepGoog = Json.obj("url" -> "http://google.com", "isPrivate" -> false)
      val keepBing = Json.obj("url" -> "http://bing.com", "isPrivate" -> false)
      val keepStanford = Json.obj("url" -> "http://stanford.edu", "isPrivate" -> false)
      val keepApple = Json.obj("url" -> "http://apple.com", "isPrivate" -> false)

      implicit val context = HeimdalContext.empty
      val userRepo = inject[UserRepo]
      val uriRepo = inject[NormalizedURIRepo]
      val urlRepo = inject[URLRepo]
      val keepRepo = inject[KeepRepo]
      val keepDiscoveryRepo = inject[KeepDiscoveryRepo]
      val rekeepRepo = inject[ReKeepRepo]
      val userExpRepo = inject[UserExperimentRepo]
      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport
      val db = inject[Database]

      val (u1, u2, u3, u4) = db.readWrite { implicit session =>
        val u1 = userRepo.save(User(firstName = "Shanee", lastName = "Smith"))
        val u2 = userRepo.save(User(firstName = "Foo", lastName = "Bar"))
        val u3 = userRepo.save(User(firstName = "Discoveryer", lastName = "DiscoveryetyDiscoveryyDiscovery"))
        val u4 = userRepo.save(User(firstName = "Ro", lastName = "Bot"))

        (u1, u2, u3, u4)
      }
      val bookmarkInterner = inject[KeepInterner]
      val raw1 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keep42, keepKifi))
      val raw2 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepBing))
      val raw3 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepStanford))
      val raw4 = inject[RawBookmarkFactory].toRawBookmarks(Json.arr(keepKifi, keepGoog, keepApple))

      val (keeps1, _) = bookmarkInterner.internRawBookmarks(raw1, u1.id.get, KeepSource.email, true)
      val (keeps2, _) = bookmarkInterner.internRawBookmarks(raw2, u2.id.get, KeepSource.default, true)
      keeps1.size === 2
      keeps2.size === 3
      keeps1(1).uriId === keeps2(0).uriId

      val (kc0, kc1, kc2) = db.readWrite { implicit rw =>
        val kifiHitCache = inject[KifiHitCache]
        val origin = "https://www.google.com"
        val kc0 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u1.id.get, keepId = keeps1(0).id.get, uriId = keeps1(0).uriId))
        // u2 -> 42 (u1)
        kifiHitCache.set(KifiHitKey(u2.id.get, keeps1(0).uriId), SanitizedKifiHit(kc0.hitUUID, origin, raw1(0).url, kc0.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId), Seq.empty, None, 0, 0)))

        val ts = currentDateTime
        val uuid = ExternalId[SanitizedKifiHit]()
        val kc1 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u1.id.get, keepId = keeps1(1).id.get, uriId = keeps1(1).uriId))
        val kc2 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = ts, hitUUID = uuid, numKeepers = 2, keeperId = u2.id.get, keepId = keeps2(0).id.get, uriId = keeps2(0).uriId))
        // u3 -> kifi (u1, u2) [rekeep]
        kifiHitCache.set(KifiHitKey(u3.id.get, keeps1(1).uriId), SanitizedKifiHit(kc1.hitUUID, origin, raw1(1).url, kc1.uriId, KifiHitContext(false, false, 0, Seq(u1.externalId, u2.externalId), Seq.empty, None, 0, 0)))

        (kc0, kc1, kc2)
      }

      val (keeps3, _) = bookmarkInterner.internRawBookmarks(raw3, u3.id.get, KeepSource.default, true)

      val kc3 = db.readWrite { implicit rw =>
        val kifiHitCache = inject[KifiHitCache]
        val origin = "https://www.google.com"
        val kc3 = keepDiscoveryRepo.save(KeepDiscovery(createdAt = currentDateTime, hitUUID = ExternalId[SanitizedKifiHit](), numKeepers = 1, keeperId = u3.id.get, keepId = keeps3(0).id.get, uriId = keeps3(0).uriId))
        // u4 -> kifi (u3) [rekeep]
        kifiHitCache.set(KifiHitKey(u4.id.get, keeps3(0).uriId), SanitizedKifiHit(kc3.hitUUID, origin, raw3(0).url, kc3.uriId, KifiHitContext(false, false, 0, Seq(u3.externalId), Seq.empty, None, 0, 0)))
        kc3
      }

      val (keeps4, _) = bookmarkInterner.internRawBookmarks(raw4, u4.id.get, KeepSource.default, true)

      val (keeps, clickCount, rekeepCount, clicks, rekeeps) = db.readOnly {implicit s =>
        val keeps = keepRepo.getByUser(u1.id.get, None, None, 100)
        val clickCount = keepDiscoveryRepo.getDiscoveryCountByKeeper(u1.id.get)
        val clicks = keepDiscoveryRepo.getDiscoveryCountsByKeeper(u1.id.get)
        val rekeepCount = rekeepRepo.getReKeepCountByKeeper(u1.id.get)
        val rekeeps = rekeepRepo.getReKeepCountsByKeeper(u1.id.get)
        (keeps, clickCount, rekeepCount, clicks, rekeeps)
      }
      keeps.size === keeps1.size
      clickCount === 2
      rekeepCount === 1
      clicks.keySet.size === 2
      rekeeps.keySet.size === 1

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allKeeps(before = Some(keeps1(0).externalId.toString), after = None, collection = None, helprank = Some("click")).toString
      path === s"/m/1/keeps/all?before=${keeps1(0).externalId.toString}&helprank=click"
      inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
      val sharingUserInfo = Seq(SharingUserInfo(Set(u2.id.get), 3), SharingUserInfo(Set(), 0))
      inject[FakeSearchServiceClient].sharingUserInfoData(sharingUserInfo)

      val controller = inject[KeepsController]
      inject[FakeActionAuthenticator].setUser(u1)

      import play.api.Play.current
      println("global id: " + current.global.asInstanceOf[com.keepit.FortyTwoGlobal].globalId)

      Await.result(inject[FakeSearchServiceClient].sharingUserInfo(null, Seq()), Duration(1, SECONDS)) === sharingUserInfo
      val request = FakeRequest("GET", path)
      val result = route(request).get
      status(result) must equalTo(OK)
      contentType(result) must beSome("application/json")

      val expected = Json.parse(s"""
                  {"collection":null,
                   "before":"${keeps1(0).externalId.toString}",
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(1).externalId.toString}",
                      "url":"${keeps1(1).url}",
                      "isPrivate":${keeps1(1).isPrivate},
                      "createdAt":"${keeps1(1).createdAt.toStandardTimeString}",
                      "others":1,
                      "keepers":[{"id":"${u2.externalId.toString}","firstName":"${u2.firstName}","lastName":"${u2.lastName}","pictureName":"0.jpg"}],
                      "clickCount":1,
                      "collections":[],
                      "tags":[],
                      "siteName":"kifi.com",
                      "clickCount":1,
                      "rekeepCount":1
                    }
                  ],
                  "helprank":"click"
                  }
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

      val request = FakeRequest("GET", s"/m/1/keeps/all?after=${bookmark1.externalId.toString}")
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

  "saveCollection create mode" in  {
    running(new ShoeboxApplication(controllerTestModules:_*)) {
      val user = inject[Database].readWrite { implicit session =>
        inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
      }

      val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.saveCollection().toString
      path === "/m/1/collections/create"

      val json = Json.obj("name" -> JsString("my tag"))
      inject[FakeActionAuthenticator].setUser(user)
      val controller = inject[MobileBookmarksController]
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

      val expected = Json.parse(s"""{"id":"${collection.externalId}","name":"my tag"}""")
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "MobileBookmarksController" should {

    "allCollections" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val (user, collections) = inject[Database].readWrite { implicit session =>
          val user = inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction1")) ::
                            collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction2")) ::
                            collectionRepo.save(Collection(userId = user.id.get, name = "myCollaction3")) ::
                            Nil
          (user, collections)
        }

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.allCollections().toString
        path === "/m/1/collections/all"

        inject[FakeActionAuthenticator].setUser(user)
        val controller = inject[MobileBookmarksController]
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
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
          KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
          KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
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

        sourceForTitle("title 11") === KeepSource.mobile
        sourceForTitle("title 21") === KeepSource.mobile
        sourceForTitle("title 31") === KeepSource.mobile

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

    "addKeeps" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
        val user = inject[Database].readWrite { implicit session =>
          inject[UserRepo].save(User(firstName = "Eishay", lastName = "Smith"))
        }
        val withCollection =
          KeepInfo(id = None, title = Some("title 11"), url = "http://www.hi.com11", isPrivate = false) ::
          KeepInfo(id = None, title = Some("title 21"), url = "http://www.hi.com21", isPrivate = true) ::
          KeepInfo(id = None, title = Some("title 31"), url = "http://www.hi.com31", isPrivate = false) ::
          Nil
        val keepsAndCollections = KeepInfosWithCollection(Some(Right("myTag")), withCollection)

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.addKeeps().toString
        path === "/m/2/keeps/add"

        val json = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        inject[FakeActionAuthenticator].setUser(user)
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = route(request).get
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        sourceForTitle("title 11") === KeepSource.mobile
        sourceForTitle("title 21") === KeepSource.mobile
        sourceForTitle("title 31") === KeepSource.mobile

        stateForTitle("title 11") === "active"
        stateForTitle("title 21") === "active"
        stateForTitle("title 31") === "active"

        val expected = Json.parse(s"""
          {
            "keepCount":3,
            "addedToCollection":3
          }
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "unkeepMultiple" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
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
        val controller = inject[MobileBookmarksController]
        val keepJson = Json.obj(
          "collectionName" -> JsString(keepsAndCollections.collection.get.right.get),
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.mobile.routes.MobileBookmarksController.keepMultiple().toString).withJsonBody(keepJson)
        val keepRes = route(keepReq).get
        status(keepRes) must equalTo(OK);
        contentType(keepRes) must beSome("application/json");

        sourceForTitle("title 11") === KeepSource.mobile
        sourceForTitle("title 21") === KeepSource.mobile
        sourceForTitle("title 31") === KeepSource.mobile

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.unkeepMultiple().toString
        path === "/m/1/keeps/remove"

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

    "unkeepBatch" in  {
      running(new ShoeboxApplication(controllerTestModules:_*)) {
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
          "keeps" -> JsArray(keepsAndCollections.keeps map {k => Json.toJson(k)})
        )
        val keepReq = FakeRequest("POST", com.keepit.controllers.mobile.routes.MobileBookmarksController.keepMultiple().toString).withJsonBody(keepJson)
        val keepRes = route(keepReq).get
        status(keepRes) must equalTo(OK)
        contentType(keepRes) must beSome("application/json")
        val keepJsonRes = Json.parse(contentAsString(keepRes))
        val savedKeeps = (keepJsonRes \ "keeps").as[Seq[KeepInfo]]
        savedKeeps.length === withCollection.size
        savedKeeps.forall(k => k.id.nonEmpty) === true

        sourceForTitle("title 11") === KeepSource.mobile
        sourceForTitle("title 21") === KeepSource.mobile
        sourceForTitle("title 31") === KeepSource.mobile

        val path = com.keepit.controllers.mobile.routes.MobileBookmarksController.unkeepBatch().toString
        path === "/m/1/keeps/delete" // remove already taken

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

  }
}

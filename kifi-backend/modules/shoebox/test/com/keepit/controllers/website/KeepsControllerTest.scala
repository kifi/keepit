package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db._
import com.keepit.common.db.slick.Database

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal._
import com.keepit.model._
import com.keepit.search._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class KeepsControllerTest extends Specification with ShoeboxTestInjector with HelpRankTestHelper {

  val controllerTestModules = Seq(
    FakeUserActionsModule(),
    FakeHttpClientModule(),
    FakeGraphServiceModule(),
    FakeShoeboxServiceModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCortexServiceClientModule()
  )

  def deepTest(a: JsValue, b: JsValue, path: Option[String] = None): Option[String] = {
    (a.asOpt[JsObject], b.asOpt[JsObject]) match {
      case (Some(aObj), Some(bObj)) =>
        (aObj.keys ++ bObj.keys).flatMap(k => deepTest(aObj \ k, bObj \ k, path.map(_ + k))).headOption
      case _ =>
        (a.asOpt[JsArray], b.asOpt[JsArray]) match {
          case (Some(aArr), Some(bArr)) if aArr.value.length != bArr.value.length =>
            Some(s"${path.getOrElse("")}: lengths unequal")
          case (Some(aArr), Some(bArr)) =>
            (aArr.value zip bArr.value).flatMap { case (av, bv) => deepTest(av, bv, path.map(_ + "[i]")) }.headOption
          case _ if a != b =>
            println(s"Found discrepancy: $a != $b")
            Some(s"${path.getOrElse("")}: $a != $b")
          case _ => None
        }
    }
  }

  def externalIdForTitle(title: String)(implicit injector: Injector): String = forTitle(title).externalId.id
  def externalIdForCollection(userId: Id[User], name: String)(implicit injector: Injector): String = forCollection(userId, name).externalId.id

  def sourceForTitle(title: String)(implicit injector: Injector): KeepSource = forTitle(title).source

  def stateForTitle(title: String)(implicit injector: Injector): String = forTitle(title).state.value

  def forTitle(title: String)(implicit injector: Injector): Keep = {
    // hilariously inefficient, but it's only for testing
    inject[Database].readWrite { implicit session =>
      val keeps = inject[KeepRepo].all().filter(_.title == title)
      keeps.size === 1
      keeps.head
    }
  }

  def forCollection(userId: Id[User], name: String)(implicit injector: Injector): Collection = {
    inject[Database].readWrite { implicit session =>
      val collections = inject[CollectionRepo].getByUserAndName(userId, Hashtag(name))
      collections.size === 1
      collections.head
    }
  }

  def libraryCard(libraryId: Id[Library])(implicit injector: Injector): LibraryCardInfo = {
    val viewerOpt = inject[FakeUserActionsHelper].fixedUser.flatMap(_.id)
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val owner = basicUserRepo.load(library.ownerId)
      inject[LibraryCardCommander].createLibraryCardInfo(library, owner, viewerOpt, withFollowing = true, ProcessedImageSize.Medium.idealSize)
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

        val (user1, user2, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withCreatedAt(t1).withName("Andrew", "C").withUsername("test").saved
          val user2 = UserFactory.user().withCreatedAt(t2).withName("Eishay", "S").withUsername("test").saved

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), keptAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url,
            uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), keptAt = t2.plusDays(1), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

          (user1, user2, bookmark1, bookmark2, bookmark3, lib1)
        }
        implicit val pubIdConfig = inject[PublicIdConfiguration]
        val pubLibId1 = Library.publicId(lib1.id.get)
        val keeps = db.readWrite { implicit s =>
          keepRepo.getByUser(user1.id.get, None, None, 100)
        }
        keeps.size === 2

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = None, after = None, collection = None, helprank = None).toString
        path === "/site/keeps/all"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
        inject[FakeSearchServiceClient].setKeepers((Seq(bookmark1.userId, user2.id.get), 3), (Seq(bookmark2.userId), 1))

        inject[FakeUserActionsHelper].setUser(user1)

        val request = FakeRequest()
        val result = inject[KeepsController].allKeeps(before = None, after = None, collectionOpt = None, helprankOpt = None, count = 20, withPageInfo = false)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.obj(
          "collection" -> JsNull,
          "before" -> JsNull,
          "after" -> JsNull,
          "keeps" -> Json.arr(
            Json.obj(
              "id" -> bookmark2.externalId,
              "pubId" -> Keep.publicId(bookmark2.id.get),
              "title" -> "A1",
              "url" -> "http://www.amazon.com/",
              "isPrivate" -> false,
              "user" -> Json.obj("id" -> s"${user1.externalId}", "firstName" -> "Andrew", "lastName" -> "C", "pictureName" -> "0.jpg", "username" -> "test"),
              "createdAt" -> bookmark2.createdAt.toStandardTimeString,
              "keeps" -> Json.arr(Json.obj("id" -> bookmark2.externalId, "mine" -> true, "removable" -> true, "visibility" -> bookmark2.visibility, "libraryId" -> pubLibId1.id)),
              "keepers" -> Json.arr(Json.obj("id" -> user2.externalId, "firstName" -> "Eishay", "lastName" -> "S", "pictureName" -> "0.jpg", "username" -> "test")),
              "keepersOmitted" -> 0,
              "keepersTotal" -> 3,
              "libraries" -> Json.arr(),
              "librariesOmitted" -> 0,
              "librariesTotal" -> 0,
              "collections" -> Json.arr(),
              "tags" -> Json.arr(),
              "hashtags" -> Json.arr(),
              "summary" -> Json.obj(),
              "siteName" -> "Amazon",
              "libraryId" -> pubLibId1.id,
              "library" -> Json.toJson(libraryCard(lib1.id.get)),
              "messages" -> Json.arr()
            ),
            Json.obj(
              "id" -> bookmark1.externalId,
              "pubId" -> Keep.publicId(bookmark1.id.get),
              "title" -> "G1",
              "url" -> "http://www.google.com/",
              "isPrivate" -> false,
              "user" -> Json.obj("id" -> user1.externalId, "firstName" -> "Andrew", "lastName" -> "C", "pictureName" -> "0.jpg", "username" -> "test"),
              "createdAt" -> bookmark1.createdAt.toStandardTimeString,
              "keeps" -> Json.arr(
                Json.obj("id" -> bookmark1.externalId, "mine" -> true, "removable" -> true, "visibility" -> bookmark1.visibility, "libraryId" -> pubLibId1.id),
                Json.obj("id" -> bookmark3.externalId, "mine" -> false, "removable" -> true, "visibility" -> bookmark3.visibility, "libraryId" -> pubLibId1.id)
              ),
              "keepers" -> Json.arr(),
              "keepersOmitted" -> 0,
              "keepersTotal" -> 1,
              "libraries" -> Json.arr(),
              "librariesOmitted" -> 0,
              "librariesTotal" -> 0,
              "collections" -> Json.arr(),
              "tags" -> Json.arr(),
              "hashtags" -> Json.arr(),
              "summary" -> Json.obj(),
              "siteName" -> "Google",
              "libraryId" -> pubLibId1.id,
              "library" -> libraryCard(lib1.id.get),
              "messages" -> Json.arr()
            )
          )
        )
        val actual = contentAsJson(result).as[JsObject]
        deepTest(actual, expected) must beNone
        1 === 1
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

        val (user1, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withCreatedAt(t1).withName("Andrew", "C").withUsername("test").saved
          val user2 = UserFactory.user().withCreatedAt(t2).withName("Eishay", "S").withUsername("test").saved

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("asdf"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          val bookmark1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
            uriId = uri1.id.get, source = keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bookmark2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
            uriId = uri2.id.get, source = keeper, createdAt = t1.plusHours(50), keptAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))
          val bookmark3 = keepRepo.save(Keep(title = None, userId = user2.id.get, url = url1.url,
            uriId = uri1.id.get, source = initLoad, createdAt = t2.plusDays(1), keptAt = t2.plusDays(1), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get)))

          (user1, bookmark1, bookmark2, bookmark3, lib1)
        }
        implicit val pubIdConfig = inject[PublicIdConfiguration]
        val pubLibId1 = Library.publicId(lib1.id.get)
        val keeps = db.readWrite { implicit s =>
          keepRepo.getByUser(user1.id.get, None, None, 100)
        }
        keeps.size === 2

        inject[FakeUserActionsHelper].setUser(user1)

        inject[FakeSearchServiceClient].setKeepers((Seq(bookmark1.userId), 1), (Seq(bookmark2.userId), 1))

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
                "pubId":"${Keep.publicId(bookmark2.id.get).id}",
                "title":"A1",
                "url":"http://www.amazon.com/",
                "isPrivate":false,
                "user":{"id":"${user1.externalId}","firstName":"Andrew","lastName":"C","pictureName":"0.jpg","username":"test"},
                "createdAt":"2013-02-16T23:59:00.000Z",
                "keeps":[{"id":"${bookmark2.externalId}", "mine":true, "removable":true, "visibility":"${bookmark2.visibility.value}", "libraryId":"${pubLibId1.id}"}],
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
                "libraryId":"${pubLibId1.id}",
                "library":${Json.toJson(libraryCard(lib1.id.get))},
                "messages":[]
              }
            ]
          }
        """)
        val actual = contentAsJson(result)
        deepTest(actual, expected) must beNone
      }
    }

    "allKeeps with helprank" in {
      withDb(controllerTestModules: _*) { implicit injector =>

        implicit val context = HeimdalContext.empty
        val keepRepo = inject[KeepRepo]
        val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
        val db = inject[Database]

        val (u1: User, u2: User, u3, keeps1: Seq[Keep], keeps2: Seq[Keep], keeps3: Seq[Keep], u1Main: Library, _) = helpRankSetup(heimdal, db)

        val keeps = db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(u1.id.get, None, None, 100)
        }
        keeps.size === keeps1.size

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = None, after = None, collection = None, helprank = Some("click")).toString
        path === "/site/keeps/all?helprank=click"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
        inject[FakeSearchServiceClient].setKeepers((Seq(keeps1(1).userId, u2.id.get), 3), (Seq(keeps1(0).userId), 1))

        inject[FakeUserActionsHelper].setUser(u1)

        implicit val publicIdConfiguration = inject[PublicIdConfiguration]

        val request = FakeRequest("GET", path)
        val result = inject[KeepsController].allKeeps(before = None, after = None, collectionOpt = None, helprankOpt = Some("click"), count = 20, withPageInfo = false)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
                  {"collection":null,
                   "before":null,
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(1).externalId.toString}",
                      "pubId": "${Keep.publicId(keeps1(1).id.get).id}",
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
                      "libraryId":"${Library.publicId(u1Main.id.get)(inject[PublicIdConfiguration]).id}",
                      "library":${Json.toJson(libraryCard(u1Main.id.get))},
                      "messages":[]
                    },
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "pubId": "${Keep.publicId(keeps1(0).id.get).id}",
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
                      "libraryId":"l7jlKlnA36Su",
                      "library":${Json.toJson(libraryCard(u1Main.id.get))},
                      "messages":[]
                    }
                  ],
                  "helprank":"click"
                  }
                """)

        val actual = contentAsJson(result)
        deepTest(actual, expected) must beNone
      }
    }

    "allKeeps with helprank & before" in {
      withDb(controllerTestModules: _*) { implicit injector =>

        implicit val context = HeimdalContext.empty
        val keepRepo = inject[KeepRepo]
        val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
        val db = inject[Database]

        val (u1: User, u2: User, u3, keeps1: Seq[Keep], keeps2: Seq[Keep], keeps3: Seq[Keep], u1Main: Library, _) = helpRankSetup(heimdal, db)

        val keeps = db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(u1.id.get, None, None, 100)
        }
        keeps.size === keeps1.size

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = Some(keeps1(1).externalId.toString), after = None, collection = None, helprank = Some("click")).toString
        path === s"/site/keeps/all?before=${keeps1(1).externalId.toString}&helprank=click"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]
        inject[FakeSearchServiceClient].setKeepers((Seq(keeps1(1).userId, u2.id.get), 3))

        inject[FakeUserActionsHelper].setUser(u1)

        implicit val publicIdConfiguration = inject[PublicIdConfiguration]

        val request = FakeRequest("GET", path)
        val result = inject[KeepsController].allKeeps(before = Some(keeps1(1).externalId.toString), after = None, collectionOpt = None, helprankOpt = Some("click"), count = 20, withPageInfo = false)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
                  {"collection":null,
                   "before":"${keeps1(1).externalId.toString}",
                   "after":null,
                   "keeps":[
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "pubId": "${Keep.publicId(keeps1(0).id.get).id}",
                      "url":"${keeps1(0).url}",
                      "isPrivate":${keeps1(0).isPrivate},
                      "user":{"id":"${u1.externalId}","firstName":"Shanee","lastName":"Smith","pictureName":"0.jpg","username":"test"},
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
                      "libraryId":"l7jlKlnA36Su",
                      "library":${Json.toJson(libraryCard(u1Main.id.get))},
                      "messages":[]
                    }
                  ],
                  "helprank":"click"
                  }
                """)
        val actual = contentAsJson(result)
        deepTest(actual, expected) must beNone
      }
    }

    "allKeeps with helprank & count" in {
      withDb(controllerTestModules: _*) { implicit injector =>

        implicit val context = HeimdalContext.empty
        val keepRepo = inject[KeepRepo]
        val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
        val db = inject[Database]

        val (u1: User, u2: User, u3, keeps1: Seq[Keep], keeps2: Seq[Keep], keeps3: Seq[Keep], u1Main: Library, u3Main: Library) = helpRankSetup(heimdal, db)

        val keeps = db.readOnlyMaster { implicit s =>
          keepRepo.getByUser(u1.id.get, None, None, 100)
        }
        keeps.size === keeps1.size

        val path = com.keepit.controllers.website.routes.KeepsController.allKeeps(before = None, after = None, collection = None, helprank = Some("click"), count = 2).toString
        path === s"/site/keeps/all?helprank=click&count=2"
        inject[FakeSearchServiceClient] === inject[FakeSearchServiceClient]

        inject[FakeUserActionsHelper].setUser(u3)

        implicit val publicIdConfiguration = inject[PublicIdConfiguration]

        val request = FakeRequest("GET", path)
        val result = inject[KeepsController].allKeeps(before = None, after = None, collectionOpt = None, helprankOpt = Some("click"), count = 2, withPageInfo = false)(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
                                  {"collection":null,
                                   "before":null,
                                   "after":null,
                                   "keeps":[
                                    {
                                      "id":"${keeps3(2).externalId.toString}",
                                      "pubId": "${Keep.publicId(keeps3(2).id.get).id}",
                                      "url":"${keeps3(2).url}",
                                      "isPrivate":${keeps3(2).isPrivate},
                                      "user":{"id":"${u3.externalId}","firstName":"Discoveryer","lastName":"DiscoveryetyDiscoveryyDiscovery","pictureName":"0.jpg","username":"test"},
                                      "createdAt":"${keeps3(2).keptAt.toStandardTimeString}",
                                      "keeps":[{"id":"${keeps3(2).externalId}", "mine":true, "removable":true, "visibility":"${keeps3(2).visibility.value}", "libraryId":"lzmfsKLJyou6"}],
                                      "keepers":[],
                                      "keepersOmitted": 0,
                                      "keepersTotal": 0,
                                      "libraries":[],
                                      "librariesOmitted": 0,
                                      "librariesTotal": 0,
                                      "collections":[],
                                      "tags":[],
                                      "hashtags":[],
                                      "summary":{},
                                      "siteName":"Facebook",
                                      "libraryId":"lzmfsKLJyou6",
                                      "library":${Json.toJson(libraryCard(u3Main.id.get))},
                                      "messages":[]
                                    },
                                    {
                                      "id":"${keeps3(0).externalId.toString}",
                                      "pubId": "${Keep.publicId(keeps3(0).id.get).id}",
                                      "url":"${keeps3(0).url}",
                                      "isPrivate":${keeps3(0).isPrivate},
                                      "user":{"id":"${u3.externalId}","firstName":"Discoveryer","lastName":"DiscoveryetyDiscoveryyDiscovery","pictureName":"0.jpg","username":"test"},
                                      "createdAt":"${keeps3(0).keptAt.toStandardTimeString}",
                                      "keeps":[{"id":"${keeps3(0).externalId}", "mine":true, "removable":true, "visibility":"${keeps3(0).visibility.value}", "libraryId":"lzmfsKLJyou6"}],
                                      "keepers":[],
                                      "keepersOmitted": 0,
                                      "keepersTotal": 0,
                                      "libraries":[],
                                      "librariesOmitted": 0,
                                      "librariesTotal": 0,
                                      "collections":[],
                                      "tags":[],
                                      "hashtags":[],
                                      "summary":{},
                                      "siteName":"Kifi",
                                      "libraryId":"lzmfsKLJyou6",
                                      "library":${Json.toJson(libraryCard(u3Main.id.get))},
                                      "messages":[]
                                    }
                                  ],
                                  "helprank":"click"
                                  }
                                """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "allCollections (default sorting)" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = inject[Database].readWrite { implicit session =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          val collections = inject[CollectionRepo].save(Collection(userId = user.id.get, name = Hashtag("myCollaction1"))) ::
            inject[CollectionRepo].save(Collection(userId = user.id.get, name = Hashtag("myCollaction2"))) ::
            inject[CollectionRepo].save(Collection(userId = user.id.get, name = Hashtag("myCollaction3"))) ::
            Nil
          (user, collections)
        }

        val path = com.keepit.controllers.website.routes.KeepsController.allCollections().toString
        path === "/site/collections/all"

        inject[FakeUserActionsHelper].setUser(user)
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

    "allCollections (numKeeps sorting)" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 9, 1, 21, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val user1 = db.readWrite { implicit session =>
          UserFactory.user().withName("Mega", "Tron").withUsername("test").saved
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user1.id.get)
        db.readWrite { implicit session =>
          val tagA = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagA"), createdAt = t1))
          val tagB = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagB"), createdAt = t1))
          val tagC = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagC"), createdAt = t1.plusMinutes(1)))
          val tagD = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagD"), createdAt = t1.plusMinutes(2)))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val mainLib = libraryRepo.getBySpaceAndSlug(LibrarySpace.fromUserId(user1.id.get), LibrarySlug("main"))
          val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))
          val keep2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(1)))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(3)))
          collectionRepo.save(tagA.copy(lastKeptTo = Some(t1.plusMinutes(3))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagC.id.get, createdAt = t1.plusMinutes(4)))
          collectionRepo.save(tagC.copy(lastKeptTo = Some(t1.plusMinutes(4))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagD.id.get, createdAt = t1.plusMinutes(6)))
          collectionRepo.save(tagD.copy(lastKeptTo = Some(t1.plusMinutes(6))))
        }

        val path = com.keepit.controllers.website.routes.KeepsController.allCollections().url
        path === "/site/collections/all"

        inject[FakeUserActionsHelper].setUser(user1)
        val controller = inject[KeepsController]
        val request = FakeRequest("GET", path)
        val result = controller.allCollections("num_keeps")(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        val expected = Json.parse(s"""
           {"keeps":2,
            "collections":[
               {"id":"${externalIdForCollection(user1.id.get, "tagA")}","name":"tagA","keeps":2},
               {"id":"${externalIdForCollection(user1.id.get, "tagD")}","name":"tagD","keeps":1},
               {"id":"${externalIdForCollection(user1.id.get, "tagC")}","name":"tagC","keeps":1},
               {"id":"${externalIdForCollection(user1.id.get, "tagB")}","name":"tagB","keeps":0}
             ]}
             """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "saveCollection create mode" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection().toString
        path === "/site/collections/create"

        val json = Json.obj("name" -> JsString("my tag"))
        inject[FakeUserActionsHelper].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[KeepsController].saveCollection()(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("application/json");

        val collection = inject[Database].readWrite { implicit session =>
          val collections = inject[CollectionRepo].getUnfortunatelyIncompleteTagSummariesByUser(user.id.get)
          collections.size === 1
          collections.head
        }
        collection.name.tag === "my tag"

        val expected = Json.parse(s"""
          {"id":"${collection.externalId}","name":"my tag"}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "saveCollection create mode with long name" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user = inject[Database].readWrite { implicit session =>
          UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
        }

        val path = com.keepit.controllers.website.routes.KeepsController.saveCollection().toString

        val json = Json.obj("name" -> JsString("my tag is very very very very very very very very very very very very very very very very very long"))
        inject[FakeUserActionsHelper].setUser(user)
        val controller = inject[KeepsController]
        val request = FakeRequest("POST", path).withJsonBody(json)
        val result = inject[KeepsController].saveCollection()(request)
        status(result) must equalTo(400)
      }
    }

    "reorder tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, oldOrdering, tagA, tagB, tagC, tagD) = inject[Database].readWrite { implicit session =>
          val user1 = UserFactory.user().withName("Tony", "Stark").withUsername("test2").saved

          val tagA = Collection(userId = user1.id.get, name = Hashtag("tagA"))
          val tagB = Collection(userId = user1.id.get, name = Hashtag("tagB"))
          val tagC = Collection(userId = user1.id.get, name = Hashtag("tagC"))
          val tagD = Collection(userId = user1.id.get, name = Hashtag("tagD"))

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

        inject[FakeUserActionsHelper].setUser(user)

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

        val result2 = inject[KeepsController].updateCollectionIndexOrdering()(request2)
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

        val result3 = inject[KeepsController].updateCollectionIndexOrdering()(request3)
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

    "search tags for user" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 9, 1, 21, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val user1 = db.readWrite { implicit session =>
          UserFactory.user().withName("Mega", "Tron").withUsername("test").saved
        }
        inject[LibraryCommander].internSystemGeneratedLibraries(user1.id.get)
        db.readWrite { implicit session =>
          val tagA = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagA"), createdAt = t1))
          val tagB = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagB"), createdAt = t1))
          val tagC = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagC"), createdAt = t1.plusMinutes(1)))
          val tagD = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("tagD"), createdAt = t1.plusMinutes(2)))

          uriRepo.count === 0
          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val mainLib = libraryRepo.getBySpaceAndSlug(LibrarySpace.fromUserId(user1.id.get), LibrarySlug("main"))
          val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = user1.id.get, url = url1.url,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))
          val keep2 = keepRepo.save(Keep(title = Some("A1"), userId = user1.id.get, url = url2.url,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1, keptAt = t1, state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(mainLib.get.id.get)))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(1)))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = tagA.id.get, createdAt = t1.plusMinutes(3)))
          collectionRepo.save(tagA.copy(lastKeptTo = Some(t1.plusMinutes(3))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagC.id.get, createdAt = t1.plusMinutes(4)))
          collectionRepo.save(tagC.copy(lastKeptTo = Some(t1.plusMinutes(4))))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = tagD.id.get, createdAt = t1.plusMinutes(6)))
          collectionRepo.save(tagD.copy(lastKeptTo = Some(t1.plusMinutes(6))))

          (user1)
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("GET", com.keepit.controllers.website.routes.KeepsController.searchUserTags("").url)
        val result1 = inject[KeepsController].searchUserTags("ta")(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
           |{ "results":
              |[
              |  { "tag":"tagA","keepCount":2,"matches":[[0,2]] },
              |  { "tag":"tagC","keepCount":1,"matches":[[0,2]] },
              |  { "tag":"tagD","keepCount":1,"matches":[[0,2]] }
              |]
           | }
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)
      }
    }
  }
}

package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.helprank.HelpRankTestHelper
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.heimdal._
import com.keepit.model.{ KeepToCollection, _ }
import com.keepit.search.{ FakeSearchServiceClientModule, _ }
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
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
    FakeSocialGraphModule()
  )

  import com.keepit.commanders.RawBookmarkRepresentation._

  implicit val helperFormat = RawBookmarkRepresentation.helperFormat

  implicit def pubIdConfig(implicit injetesctor: Injector) = inject[PublicIdConfiguration]

  // NOTE: No attemp to write the trait SourceAttribution
  implicit val rawBookmarkRepwrites = new Writes[RawBookmarkRepresentation] {
    def writes(keep: RawBookmarkRepresentation): JsValue = {
      val tmp = RawBookmarkRepresentationWithoutAttribution(keep.title, keep.url, keep.canonical, keep.openGraph, keep.keptAt, keep.note)
      Json.toJson(tmp)
    }
  }

  def prenormalize(url: String)(implicit injector: Injector): String = normalizationService.prenormalize(url).get

  def libraryCard(libraryId: Id[Library])(implicit injector: Injector): LibraryCardInfo = {
    val viewerOpt = inject[FakeUserActionsHelper].fixedUser.flatMap(_.id)
    db.readOnlyMaster { implicit session =>
      val library = libraryRepo.get(libraryId)
      val owner = basicUserRepo.load(library.ownerId)
      inject[LibraryCardCommander].createLibraryCardInfo(library, owner, viewerOpt, withFollowing = true, ProcessedImageSize.Medium.idealSize)
    }
  }

  "allKeeps" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport

      val (user1, user2, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
        val user1 = UserFactory.user().withName("Andrew", "C").withCreatedAt(t1).withUsername("test1").saved
        val user2 = UserFactory.user().withName("Eishay", "S").withCreatedAt(t2).withUsername("test").saved

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
      val pubLibId1 = Library.publicId(lib1.id.get)
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
            "pubId":"${Keep.publicId(bookmark2.id.get).id}",
            "title":"A1",
            "url":"http://www.amazon.com",
            "path":"${bookmark2.path.relative}",
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
            "libraryId":"${pubLibId1.id}",
            "library": ${Json.toJson(libraryCard(lib1.id.get))}
            },
          {
            "id":"${bookmark1.externalId.toString}",
            "pubId":"${Keep.publicId(bookmark1.id.get).id}",
            "title":"G1",
            "url":"http://www.google.com",
            "path":"${bookmark1.path.relative}",
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
            "libraryId":"${pubLibId1.id}",
            "library": ${Json.toJson(libraryCard(lib1.id.get))}
            }
        ]}
      """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "allKeeps with helprank" in {
    withDb(controllerTestModules: _*) { implicit injector =>

      implicit val context = HeimdalContext.empty
      val heimdal = inject[HeimdalServiceClient].asInstanceOf[FakeHeimdalServiceClientImpl]
      val (u1: User, u2: User, _, keeps1: Seq[Keep], _, _, u1Main: Library, _) = helpRankSetup(heimdal, db)

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
                      "pubId":"${Keep.publicId(keeps1(1).id.get).id}",
                      "url":"${keeps1(1).url}",
                      "path":"${keeps1(1).path.relative}",
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
                      "libraryId":"l7jlKlnA36Su",
                      "library":${Json.toJson(libraryCard(u1Main.id.get))}
                    },
                    {
                      "id":"${keeps1(0).externalId.toString}",
                      "pubId":"${Keep.publicId(keeps1(0).id.get).id}",
                      "url":"${keeps1(0).url}",
                      "path":"${keeps1(0).path.relative}",
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
                      "library":${Json.toJson(libraryCard(u1Main.id.get))}
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

      val (u1: User, u2: User, _, keeps1: Seq[Keep], _, _, u1Main: Library, _) = helpRankSetup(heimdal, db)

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

      val jsonResult = contentAsJson(result)

      (jsonResult \ "before").as[ExternalId[Keep]] must beEqualTo(keeps1(1).externalId)
      (jsonResult \ "keeps").as[Seq[JsObject]].length must beEqualTo(1)
    }
  }

  "allKeeps with after" in {
    withDb(controllerTestModules: _*) { implicit injector =>
      val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
      val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)

      val keeper = KeepSource.keeper
      val initLoad = KeepSource.bookmarkImport

      val (user, bookmark1, bookmark2, bookmark3, lib1) = db.readWrite { implicit s =>
        val user1 = UserFactory.user().withName("Andrew", "C").withCreatedAt(t1).withUsername("test1").saved
        val user2 = UserFactory.user().withName("Eishay", "S").withCreatedAt(t2).withUsername("test").saved

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

      val pubLibId1 = Library.publicId(lib1.id.get)

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
                "pubId":"${Keep.publicId(bookmark2.id.get).id}",
                "title":"A1",
                "url":"http://www.amazon.com",
                "path":"${bookmark2.path.relative}",
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
                "libraryId":"${pubLibId1.id}",
                "library": ${Json.toJson(libraryCard(lib1.id.get))}
              }
            ]
          }
        """)
      Json.parse(contentAsString(result)) must equalTo(expected)
    }
  }

  "MobileKeepsController" should {

    "allCollections" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, collections) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved

          val collectionRepo = inject[CollectionRepo]
          val collections = collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction1"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction2"))) ::
            collectionRepo.save(Collection(userId = user.id.get, name = Hashtag("myCollaction3"))) ::
            Nil

          val lib = LibraryFactory.library().saved
          collections.map { c =>
            val k = KeepFactory.keep().withLibrary(lib.id.get).saved
            inject[KeepToCollectionRepo].save(KeepToCollection(keepId = k.id.get, collectionId = c.id.get))
          }
          (user, collections)
        }

        val path = com.keepit.controllers.mobile.routes.MobileKeepsController.allCollections().url
        path === "/m/1/collections/all"

        inject[FakeUserActionsHelper].setUser(user)
        val request = FakeRequest("GET", path)
        val result = inject[MobileKeepsController].allCollections(sort = "name")(request)
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
               {"id":"${ext1}","name":"myCollaction1","keeps":1},
               {"id":"${ext2}","name":"myCollaction2","keeps":1},
               {"id":"${ext3}","name":"myCollaction3","keeps":1}
            ]}
        """)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "edit note v2" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, keep1, keepWithTags, keepInactive) = db.readWrite { implicit session =>
          val user = UserFactory.user().withName("Eishay", "Smith").withUsername("test").saved
          val lib = library().withOwner(user).saved
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

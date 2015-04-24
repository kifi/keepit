package com.keepit.controllers.mobile

import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ LibraryImageCommander, KeepData }
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession.RWSession

import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ FakeShoeboxStoreModule, ImageSize }
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.{ FakeHeimdalServiceClientModule, HeimdalContext }
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.json.{ JsObject, Json }
import play.api.mvc.{ Result, Call }
import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileLibraryControllerTest extends Specification with ShoeboxTestInjector {

  val controllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeSearchServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule(),
    FakeSliderHistoryTrackerModule()
  )

  "MobileLibraryController" should {

    "create library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val user = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "R2", lastName = "D2", createdAt = t1, username = Username("r2d2"), normalizedUsername = "r2d2"))
          libraryRepo.getAllByOwner(user.id.get).length === 0
          user
        }

        // add new library
        val jsBody1 = Json.obj("name" -> "Drones stuff", "visibility" -> "published", "color" -> "purple")
        val result1 = createLibrary(user, jsBody1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson = contentAsJson(result1)
        (resultJson \ "name").as[String] === "Drones stuff"
        (resultJson \ "visibility").as[LibraryVisibility] === LibraryVisibility.PUBLISHED
        (resultJson \ "owner").as[BasicUser].firstName === "R2"
        (resultJson \ "url").as[String] === "/r2d2/drones-stuff"
        (resultJson \ "color").asOpt[LibraryColor] === Some(LibraryColor.PURPLE)

        // add library with same title
        val jsBody2 = Json.obj("name" -> "Drones stuff", "visibility" -> "secret")
        val result2 = createLibrary(user, jsBody2)
        status(result2) must equalTo(BAD_REQUEST)

        db.readOnlyMaster { implicit s =>
          libraryRepo.getAllByOwner(user.id.get).length === 1
        }
      }
    }

    "modify library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, lib1, lib2, _, _) = setupTwoUsersThreeLibraries()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubLib2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        lib1.name === "Krabby Patty"
        lib1.description must beEmpty
        lib1.visibility === LibraryVisibility.SECRET
        lib1.slug.value === "krabby-patty"
        lib1.color must beEmpty

        // change fields (to something different)
        val jsBody1 = Json.obj("newName" -> "Feed Gary", "newDescription" -> "qwer", "newVisibility" -> "secret", "newSlug" -> "feed-gary", "newColor" -> "orange")
        val result1 = modifyLibrary(user, pubLib1, jsBody1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val resultJson = contentAsJson(result1)
        (resultJson \ "name").as[String] === "Feed Gary"
        (resultJson \ "shortDescription").asOpt[String] === Some("qwer")
        (resultJson \ "visibility").as[LibraryVisibility] === LibraryVisibility.SECRET
        (resultJson \ "url").as[String] === "/spongebob/feed-gary"
        (resultJson \ "color").asOpt[LibraryColor] === Some(LibraryColor.ORANGE)

        // change a library title to an existing library's title
        val result2 = modifyLibrary(user, pubLib1, Json.obj("newName" -> lib2.name))
        status(result2) must equalTo(BAD_REQUEST)

        // change a library slug to an existing library's slug
        val result3 = modifyLibrary(user, pubLib1, Json.obj("newSlug" -> lib2.slug.value))
        status(result3) must equalTo(BAD_REQUEST)
      }
    }

    "delete library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user, lib1, lib2, _, _) = setupTwoUsersThreeLibraries()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubLib2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        db.readOnlyMaster { implicit s =>
          libraryRepo.getAllByOwner(user.id.get).length === 2
        }

        // delete both libraries
        val result1 = deleteLibrary(user, pubLib1)
        status(result1) must equalTo(NO_CONTENT)
        val result2 = deleteLibrary(user, pubLib2)
        status(result2) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          libraryRepo.getAllByOwner(user.id.get).length === 0
        }
      }
    }

    "get library by id" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1) = setupOneUserOneLibrary()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        // upload an image
        {
          val savedF = inject[LibraryImageCommander].uploadLibraryImageFromFile(fakeImage1, lib1.id.get, LibraryImagePosition(None, None), ImageSource.UserUpload, user1.id.get)(HeimdalContext.empty)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
        }

        val result1 = getLibraryById(user1, pubLib1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) === Json.parse(
          s"""
             |{
             |  "library" : {
             |    "id" : "${pubLib1.id}",
             |    "name" : "Krabby Patty",
             |    "visibility" : "secret",
             |    "slug" : "krabby-patty",
             |    "url" : "/spongebob/krabby-patty",
             |    "image":{
             |        "path":"library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png",
             |        "x":50,
             |        "y":50
             |      },
             |    "kind" : "user_created",
             |    "owner" : {
             |      "id" : "${user1.externalId}",
             |      "firstName" : "Spongebob",
             |      "lastName": "Squarepants",
             |      "pictureName":"0.jpg",
             |      "username":"spongebob"
             |    },
             |    "followers" : [],
             |    "keeps" : [],
             |    "numKeeps" : 0,
             |    "numCollaborators" : 0,
             |    "numFollowers" : 0 },
             |  "membership" : "owner"
             |}
           """.stripMargin)

        // test retrieving persona library
        val personaLib = db.readWrite { implicit s =>
          library().withName("Healthy Habits").withSlug("healthy-habits").withUser(user1).withKind(LibraryKind.SYSTEM_PERSONA).saved
        }
        val pubLib2 = Library.publicId(personaLib.id.get)(inject[PublicIdConfiguration])
        val result2 = getLibraryById(user1, pubLib2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        (contentAsJson(result2) \\ "name").map(_.as[String]) === Seq("Healthy Habits")
      }
    }

    "join library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, lib2, user2, _) = setupTwoUsersThreeLibraries()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubLib2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 1
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 1
        }

        // join a published library
        val result1 = joinLibrary(user2, pubLib2)
        status(result1) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 2
        }

        // join a secret library (no invite)
        val result2 = joinLibrary(user2, pubLib1)
        status(result2) must equalTo(FORBIDDEN)

        // join a secret library (with invite)
        val invite = db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(inviterId = user1.id.get, libraryId = lib1.id.get, userId = Some(user2.id.get), access = LibraryAccess.READ_ONLY))
        }
        val result3 = joinLibrary(user2, pubLib1)
        status(result3) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 2
          libraryInviteRepo.get(invite.id.get).state === LibraryInviteStates.ACCEPTED
        }
      }
    }

    "decline library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, _, user2, _) = setupTwoUsersThreeLibraries()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val invite = db.readWrite { implicit s =>
          libraryInviteRepo.save(LibraryInvite(inviterId = user1.id.get, libraryId = lib1.id.get, userId = Some(user2.id.get), access = LibraryAccess.READ_ONLY))
        }

        // decline library invite
        val result1 = declineLibrary(user2, pubLib1)
        status(result1) must equalTo(OK)

        db.readOnlyMaster { implicit s =>
          libraryInviteRepo.get(invite.id.get).state === LibraryInviteStates.DECLINED
        }
      }

    }

    "leave library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, lib2, user2, _) = setupTwoUsersThreeLibraries()
        val pubLib1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubLib2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])

        db.readWrite { implicit s =>
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY))
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 2
        }

        // leave a library (with membership)
        val result1 = leaveLibrary(user2, pubLib1)
        status(result1) must equalTo(OK)

        // leave a library (is owner)
        val result2 = leaveLibrary(user1, pubLib1)
        status(result2) must equalTo(BAD_REQUEST)

        db.readWrite { implicit s =>
          libraryMembershipRepo.countWithLibraryId(lib1.id.get) === 1
          libraryMembershipRepo.countWithLibraryId(lib2.id.get) === 1
        }
      }
    }

    "get library members" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val (userA, userB, userC, userD, lib1) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userB = userRepo.save(User(firstName = "Baron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userC = userRepo.save(User(firstName = "Caron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userD = userRepo.save(User(firstName = "Daron", lastName = "H", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          val lib1 = libraryRepo.save(Library(ownerId = userA.id.get, name = "Lib1", slug = LibrarySlug("lib1"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER))

          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userB.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = userC.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1.plusMinutes(2)))
          libraryInviteRepo.save(LibraryInvite(inviterId = userB.id.get, libraryId = lib1.id.get, userId = Some(userD.id.get), access = LibraryAccess.READ_ONLY, createdAt = t1.plusMinutes(4)))
          libraryInviteRepo.save(LibraryInvite(inviterId = userB.id.get, libraryId = lib1.id.get, emailAddress = Some(EmailAddress("earon@gmail.com")), access = LibraryAccess.READ_ONLY, createdAt = t1.plusMinutes(6)))

          (userA, userB, userC, userD, lib1)
        }
        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])

        val result1 = getMembers(userA, pubId1, 0, 2)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
            |{"members" :
               |[
                 |{
                   |"id":"${userB.externalId}",
                   |"firstName":"Baron",
                   |"lastName":"H",
                   |"pictureName":"0.jpg","username":"test",
                   |"membership":"read_only"
                 |},
               |{
                 |"id":"${userC.externalId}",
                 |"firstName":"Caron",
                 |"lastName":"H",
                 |"pictureName":"0.jpg","username":"test",
                 |"membership":"read_only"
               |}
               |]
             |}""".stripMargin
        ))

        val result2 = getMembers(userA, pubId1, 2, 2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            |{"members" :
              |[
                |{
                 |"id":"${userD.externalId}",
                 |"firstName":"Daron",
                 |"lastName":"H",
                 |"pictureName":"0.jpg","username":"test",
                 |"membership":"read_only",
                 |"lastInvitedAt":${Json.toJson(t1.plusMinutes(4))(internalTime.DateTimeJsonLongFormat)}
                |},
                |{
                  |"email":"earon@gmail.com",
                  |"membership":"read_only",
                  |"lastInvitedAt":${Json.toJson(t1.plusMinutes(6))(internalTime.DateTimeJsonLongFormat)}
                |}
             |]
           |}""".stripMargin
        ))
      }
    }

    "keep to library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1) = setupOneUserOneLibrary()
        db.readOnlyMaster { implicit s =>
          keepRepo.getCountByLibrary(lib1.id.get) === 0
        }

        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val keepUrl = "http://www.airbnb.com/bikinibottom"

        // add new keep
        val add1 = Json.obj("title" -> "Bikini Bottom", "url" -> keepUrl, "hashtags" -> Seq("vacation"))
        val result1 = keepToLibrary(user1, pubId1, add1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        db.readOnlyMaster { implicit s =>
          val keeps = keepRepo.getByLibrary(lib1.id.get, 0, 10)
          keeps(0).title.get === "Bikini Bottom"
          collectionRepo.getTagsByKeepId(keeps(0).id.get).map(_.tag) === Set("vacation")
        }

        // add same keep with different title & different set of tags
        val add2 = Json.obj("title" -> "Airbnb", "url" -> keepUrl, "hashtags" -> Seq("tagA", "tagB"))
        val result2 = keepToLibrary(user1, pubId1, add2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        (contentAsJson(result2) \ "hashtags").as[Seq[Hashtag]].map(_.tag) === Seq("tagA", "tagB")

        db.readOnlyMaster { implicit s =>
          val keeps = keepRepo.getByLibrary(lib1.id.get, 0, 10)
          keeps(0).title.get === "Airbnb"
          collectionRepo.getTagsByKeepId(keeps(0).id.get).map(_.tag) === Set("tagA", "tagB")
        }
      }
    }

    "unkeep from library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1) = setupOneUserOneLibrary()
        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val keep1 = db.readWrite { implicit s =>
          val keep = setupKeepInLibrary(user1, lib1, "http://www.yelp.com/krustykrab", "krustykrab", Seq("food"))
          keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("krustykrab")
          keep
        }

        val result1 = unkeepFromLibrary(user1, pubId1, keep1.externalId)
        status(result1) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq.empty
        }
      }
    }

    "get writeable libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, lib1, lib2, _, _) = setupTwoUsersThreeLibraries()
        val pubLibId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val pubLibId2 = Library.publicId(lib2.id.get)(inject[PublicIdConfiguration])
        val k1 = db.readWrite { implicit s =>
          setupKeepInLibrary(user1, lib1, "http://www.yelp.com/krustykrab", "krustykrab", Seq("food1", "food2"), Some("[#food1] [#food2]"))
        }

        val emptyBody = Json.obj()
        val url1 = Json.toJson("www.yelp.com/krustykrab")
        val url2 = Json.toJson("http://www.yelp.com/krustykrab")
        val url3 = Json.toJson("http://www.google.com")

        // no url in body
        val result1 = getSummariesWithUrl(user1, emptyBody)
        status(result1) must equalTo(OK)
        val response1 = contentAsJson(result1)
        (response1 \ "libraries").as[Seq[LibraryInfo]].length === 2

        // unparseable url in body
        println("********* Intended ERROR parsing url! *********")
        val result2 = getSummariesWithUrl(user1, Json.obj("url" -> url1))
        status(result2) must equalTo(OK)
        val response2 = contentAsJson(result2)
        (response2 \ "libraries").as[Seq[LibraryInfo]].length === 2
        (response2 \ "error").as[String] === "parse_url_error"
        println("********* End intended ERROR! *********")

        // parseable url in body (kept in other libraries)
        val result3 = getSummariesWithUrl(user1, Json.obj("url" -> url2))
        status(result3) must equalTo(OK)
        val response3 = contentAsJson(result3)
        (response3 \ "libraries").as[Seq[LibraryInfo]].length === 2
        (response3 \ "error").asOpt[String] === None
        val keepData = (response3 \ "alreadyKept")
        keepData === Json.parse(s"""
            [
             { "keep": {
                "id":"${k1.externalId}",
                "title":"krustykrab",
                "note":"",
                "imageUrl":null,
                "hashtags":["food1","food2"]
               },
               "mine":true,
               "removable":true,
               "secret":true,
               "libraryId":"${pubLibId1.id}"
              }
             ]
           """)

        val result4 = getSummariesWithUrl(user1, Json.obj("url" -> url3))
        status(result4) must equalTo(OK)
        val response4 = contentAsJson(result4)
        (response4 \ "libraries").as[Seq[LibraryInfo]].length === 2
        (response4 \ "error").asOpt[String] === None
        (response4 \ "alreadyKept").asOpt[Seq[JsObject]] === None
      }
    }

  }

  private def createLibrary(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.createLibrary()(request(routes.MobileLibraryController.createLibrary()).withBody(body))
  }

  private def modifyLibrary(user: User, libId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.modifyLibrary(libId)(request(routes.MobileLibraryController.modifyLibrary(libId)).withBody(body))
  }

  private def deleteLibrary(user: User, libId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.deleteLibrary(libId)(request(routes.MobileLibraryController.deleteLibrary(libId)))
  }

  private def joinLibrary(user: User, libId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.joinLibrary(libId)(request(routes.MobileLibraryController.joinLibrary(libId)))
  }

  private def declineLibrary(user: User, libId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.declineLibrary(libId)(request(routes.MobileLibraryController.declineLibrary(libId)))
  }

  private def leaveLibrary(user: User, libId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.leaveLibrary(libId)(request(routes.MobileLibraryController.leaveLibrary(libId)))
  }

  private def getLibraryById(user: User, libId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibraryById(libId)(request(routes.MobileLibraryController.getLibraryById(libId)))
  }

  private def getMembers(user: User, libId: PublicId[Library], offset: Int, limit: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibraryMembers(libId, offset, limit)(request(routes.MobileLibraryController.getLibraryMembers(libId, offset, limit)))
  }

  private def keepToLibrary(user: User, libId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.keepToLibrary(libId)(request(routes.MobileLibraryController.keepToLibrary(libId)).withBody(body))
  }

  private def unkeepFromLibrary(user: User, libId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.unkeepFromLibrary(libId, keepId)(request(routes.MobileLibraryController.unkeepFromLibrary(libId, keepId)))
  }

  private def getSummariesWithUrl(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibrarySummariesWithUrl()(request(routes.MobileLibraryController.getLibrarySummariesWithUrl()).withBody(body))
  }

  // User 'Spongebob' has one library called "Krabby Patty" (secret)
  private def setupOneUserOneLibrary()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 12, 1, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    db.readWrite { implicit s =>
      val user = userRepo.save(User(firstName = "Spongebob", lastName = "Squarepants", username = Username("spongebob"), normalizedUsername = "spongebob", createdAt = t1))
      val library = libraryRepo.save(Library(name = "Krabby Patty", ownerId = user.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("krabby-patty"), memberCount = 1, createdAt = t1.plusMinutes(1)))
      libraryMembershipRepo.save(LibraryMembership(userId = user.id.get, libraryId = library.id.get, access = LibraryAccess.OWNER))
      (user, library)
    }
  }

  // User 'Spongebob' has two libraries called "Krabby Patty" (secret), "Catching Jellyfish" (published)
  // User 'Patrick' has one library called "Nothing" (secret)
  private def setupTwoUsersThreeLibraries()(implicit injector: Injector) = {
    val t1 = new DateTime(2014, 12, 1, 12, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)
    val (user1, library1a) = setupOneUserOneLibrary()
    val (library1b, user2, library2a) = db.readWrite { implicit s =>
      val library1b = libraryRepo.save(Library(name = "Catching Jellyfish", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("catching-jellyfish"), memberCount = 1, createdAt = t1.plusMinutes(1)))
      libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library1b.id.get, access = LibraryAccess.OWNER))

      val user2 = userRepo.save(User(firstName = "C", lastName = "3PO", username = Username("c3po"), normalizedUsername = "c3po", createdAt = t1))
      val library2a = libraryRepo.save(Library(name = "Nothing", ownerId = user2.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("nothing"), memberCount = 1, createdAt = t1.plusMinutes(1)))
      libraryMembershipRepo.save(LibraryMembership(userId = user2.id.get, libraryId = library2a.id.get, access = LibraryAccess.OWNER))
      (library1b, user2, library2a)
    }
    (user1, library1a, library1b, user2, library2a)
  }

  private def setupKeepInLibrary(user: User, lib: Library, url: String, title: String, tags: Seq[String] = Seq.empty, note: Option[String] = None)(implicit injector: Injector, session: RWSession): Keep = {
    val uri = uriRepo.save(NormalizedURI(url = url, urlHash = UrlHash(url.hashCode.toString)))
    val urlId = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)).id.get
    val keep = keepRepo.save(Keep(
      title = Some(title), userId = user.id.get, uriId = uri.id.get, urlId = urlId, url = uri.url, note = note,
      source = KeepSource.keeper, visibility = lib.visibility, libraryId = lib.id, inDisjointLib = lib.isDisjoint))
    tags.foreach { tag =>
      val coll = collectionRepo.save(Collection(userId = keep.userId, name = Hashtag(tag)))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = coll.id.get))
    }
    keep
  }

  private def controller(implicit injector: Injector) = inject[MobileLibraryController]
  private def request(route: Call) = FakeRequest(route.method, route.url)

  private def fakeImage1 = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }
}

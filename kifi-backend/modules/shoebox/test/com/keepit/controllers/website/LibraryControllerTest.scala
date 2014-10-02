package com.keepit.controllers.website

import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ RawBookmarksWithCollection, RawBookmarkRepresentation, FullLibraryInfo, LibraryInfo }
import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScrapeSchedulerConfigModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsString, JsArray, Json }
import play.api.mvc.Result
import play.api.test.Helpers._
import play.api.test._
import com.keepit.common.json._

import scala.concurrent.Future

class LibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeCryptoModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeKeepImportsModule(),
    FakeMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerConfigModule(),
    FakeSocialGraphModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxServiceModule()
  )

  "LibraryController" should {

    "create library" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]
        val testPath = com.keepit.controllers.website.routes.LibraryController.addLibrary().url
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
        }
        val inputJson1 = Json.obj(
          "name" -> "Library1",
          "slug" -> "lib1",
          "visibility" -> "secret",
          "collaborators" -> Json.arr(),
          "followers" -> Json.arr(),
          "memberCount" -> 1
        )
        inject[FakeUserActionsHelper].setUser(user)
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1)
        val result1 = libraryController.addLibrary()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val parse1 = Json.parse(contentAsString(result1)).as[FullLibraryInfo]
        parse1.name === "Library1"
        parse1.slug.value === "lib1"
        parse1.visibility.value === "secret"
        parse1.keeps.size === 0
        parse1.owner.externalId === user.externalId

        val inputJson2 = Json.obj(
          "name" -> "Invalid Library - Slug",
          "slug" -> "lib2 abcd",
          "visibility" -> "secret",
          "collaborators" -> Json.arr(),
          "followers" -> Json.arr(),
          "memberCount" -> 1
        )
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2)
        val result2 = libraryController.addLibrary()(request2)
        status(result2) must equalTo(BAD_REQUEST)
        contentType(result2) must beSome("application/json")

        // Re-add Library 1
        val request3 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(inputJson1)
        val result3 = libraryController.addLibrary()(request3)
        status(result3) must equalTo(BAD_REQUEST)
        contentType(result3) must beSome("application/json")

        val inputJson4 = Json.obj(
          "name" -> "Invalid Name - \"",
          "slug" -> "lib5",
          "visibility" -> "secret",
          "collaborators" -> Json.arr(),
          "followers" -> Json.arr(),
          "memberCount" -> 1
        )
        val request4 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(inputJson4)
        val result4 = libraryController.addLibrary()(request4)
        status(result4) must equalTo(BAD_REQUEST)
        contentType(result4) must beSome("application/json")
      }
    }

    "modify library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Some(Username("ahsu"))))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user, library)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.modifyLibrary(pubId).url
        inject[FakeUserActionsHelper].setUser(user1)

        val inputJson1 = Json.obj("name" -> "Library2")
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1)
        val result1 = libraryController.modifyLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        Json.parse(contentAsString(result1)).as[LibraryInfo].name === "Library2"

        val inputJson2 = Json.obj("slug" -> "lib2", "description" -> "asdf", "visibility" -> LibraryVisibility.PUBLISHED.value)
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2)
        val result2 = libraryController.modifyLibrary(pubId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val basicUser1 = db.readOnlyMaster { implicit s => basicUserRepo.load(user1.id.get) }
        val expected = Json.parse(
          s"""
             |{
             |"id":"${pubId.id}",
             |"name":"Library2",
             |"visibility":"published",
             |"shortDescription":"asdf",
             |"url":"/ahsu/lib2",
             |"owner":{
             |  "id":"${basicUser1.externalId}",
             |  "firstName":"${basicUser1.firstName}",
             |  "lastName":"${basicUser1.lastName}",
             |  "pictureName":"${basicUser1.pictureName}",
             |  "username":"${basicUser1.username.get.value}"
             |  },
             |"numKeeps":0,
             |"numFollowers":0,
             |"kind":"user_created"
             |}
           """.stripMargin)
        Json.parse(contentAsString(result2)) must equalTo(expected)
      }
    }

    "remove library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val user2 = userRepo.save(User(firstName = "Someone", lastName = "Else", createdAt = t1))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, library1, library2)
        }
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.removeLibrary(pubId1).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.removeLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.removeLibrary(pubId2).url
        val request2 = FakeRequest("POST", testPath2)
        val result2 = libraryController.removeLibrary(pubId2)(request2)
        status(result2) must equalTo(FORBIDDEN)
        contentType(result2) must beSome("application/json")
      }
    }

    "get library by public id" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Some(Username("ahsu"))))

          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, library)
        }

        val pubId1 = Library.publicId(lib1.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getLibraryById(pubId1).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("GET", testPath1)
        val result1 = libraryController.getLibraryById(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val basicUser1 = db.readOnlyMaster { implicit s => basicUserRepo.load(user1.id.get) }
        val expected = Json.parse(
          s"""{
             |"library":{
               |"id":"${pubId1.id}",
               |"name":"Library1",
               |"visibility":"secret",
               |"slug":"lib1",
               |"url":"/ahsu/lib1",
               |"kind":"user_created",
               |"owner":{
               |  "id":"${basicUser1.externalId}",
               |  "firstName":"${basicUser1.firstName}",
               |  "lastName":"${basicUser1.lastName}",
               |  "pictureName":"${basicUser1.pictureName}",
               |  "username":"${basicUser1.username.get.value}"
               |  },
               |"collaborators":[],
               |"followers":[],
               |"keeps":[],
               |"numKeeps":0,
               |"numCollaborators":0,
               |"numFollowers":0
             |}
          }""".stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)
      }
    }

    "get library by path" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, lib1) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Some(Username("ahsu"))))
          val user2 = userRepo.save(User(firstName = "AyAy", lastName = "Ron", createdAt = t1, username = Some(Username("ayayron"))))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, user2, library)
        }

        val unInput = "ahsu"
        val badUserInput = "ahsuifhwoifhweof"
        val extInput = user1.externalId.id
        val slugInput = "lib1"
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get.lastViewed.isDefined === false
        }

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(unInput, slugInput).url
        val request1 = FakeRequest("GET", testPath1)
        val result1 = libraryController.getLibraryByPath(unInput, slugInput)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val firstTime = db.readOnlyMaster { implicit s =>
          val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get
          mem.lastViewed.isDefined === true
          mem.lastViewed.get
        }

        val testPath1_bad = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(badUserInput, slugInput).url
        val request1_bad = FakeRequest("GET", testPath1_bad)
        val result1_bad = libraryController.getLibraryByPath(badUserInput, slugInput)(request1_bad)
        status(result1_bad) must equalTo(BAD_REQUEST)

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(extInput, slugInput).url
        val request2 = FakeRequest("GET", testPath2)
        val result2 = libraryController.getLibraryByPath(extInput, slugInput)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val secondTime = db.readOnlyMaster { implicit s =>
          val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get
          mem.lastViewed.isDefined === true
          mem.lastViewed.get
        }
        firstTime.isBefore(secondTime) === true

        val basicUser1 = db.readOnlyMaster { implicit s => basicUserRepo.load(user1.id.get) }
        val expected = Json.parse(
          s"""{
             |"library":{
               |"id":"${Library.publicId(lib1.id.get).id}",
               |"name":"Library1",
               |"visibility":"secret",
               |"slug":"lib1",
               |"url":"/ahsu/lib1",
               |"kind":"user_created",
               |"owner":{
               |  "id":"${basicUser1.externalId}",
               |  "firstName":"${basicUser1.firstName}",
               |  "lastName":"${basicUser1.lastName}",
               |  "pictureName":"${basicUser1.pictureName}",
               |  "username":"${basicUser1.username.get.value}"
               |  },
               |"collaborators":[],
               |"followers":[],
               |"keeps":[],
               |"numKeeps":0,
               |"numCollaborators":0,
               |"numFollowers":0
             |}}""".stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)
        Json.parse(contentAsString(result2)) must equalTo(expected)
      }
    }

    "get libraries of user" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, lib1, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "A", createdAt = t1, username = Some(Username("ahsu"))))
          val user2 = userRepo.save(User(firstName = "Baron", lastName = "B", createdAt = t1, username = Some(Username("bhsu"))))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.PUBLISHED))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val library3 = libraryRepo.save(Library(name = "Library3", ownerId = user2.id.get, slug = LibrarySlug("lib3"), memberCount = 2, visibility = LibraryVisibility.DISCOVERABLE))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryInviteRepo.save(LibraryInvite(libraryId = library3.id.get, ownerId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_ONLY, state = LibraryInviteStates.ACCEPTED))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))

          // send invites to same library with different access levels (only want highest access level)
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, ownerId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_ONLY))
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, ownerId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_INSERT))
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, ownerId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_WRITE))
          (user1, user2, library1, library2, library3)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val pubId3 = Library.publicId(lib3.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.getLibrarySummariesByUser.url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("GET", testPath)
        val result1 = libraryController.getLibrarySummariesByUser()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val (basicUser1, basicUser2) = db.readOnlyMaster { implicit s => (basicUserRepo.load(user1.id.get), basicUserRepo.load(user2.id.get)) }

        val expected = Json.parse(
          s"""
            |{"libraries":
              |[
                |{
                  |"id":"${pubId1.id}",
                  |"name":"Library1",
                  |"visibility":"secret",
                  |"url":"/ahsu/lib1",
                  |"owner":{
                  |  "id":"${basicUser1.externalId}",
                  |  "firstName":"${basicUser1.firstName}",
                  |  "lastName":"${basicUser1.lastName}",
                  |  "pictureName":"${basicUser1.pictureName}",
                  |  "username":"${basicUser1.username.get.value}"
                  |  },
                  |"numKeeps":0,
                  |"numFollowers":0,
                  |"kind":"user_created",
                  |"access":"owner"
                },
                |{
                  |"id":"${pubId3.id}",
                  |"name":"Library3",
                  |"visibility":"discoverable",
                  |"url":"/bhsu/lib3",
                  |"owner":{
                  |  "id":"${basicUser2.externalId}",
                  |  "firstName":"${basicUser2.firstName}",
                  |  "lastName":"${basicUser2.lastName}",
                  |  "pictureName":"${basicUser2.pictureName}",
                  |  "username":"${basicUser2.username.get.value}"
                  |  },
                  |"numKeeps":0,
                  |"numFollowers":1,
                  |"kind":"user_created",
                  |"access":"read_only"
                |}
              |],
              |"invited":
              | [
                | {
                    |"id":"${pubId2.id}",
                    |"name":"Library2",
                    |"visibility":"published",
                    |"url":"/bhsu/lib2",
                    |"owner":{
                    |  "id":"${basicUser2.externalId}",
                    |  "firstName":"${basicUser2.firstName}",
                    |  "lastName":"${basicUser2.lastName}",
                    |  "pictureName":"${basicUser2.pictureName}",
                    |  "username":"${basicUser2.username.get.value}"
                    |  },
                    |"numKeeps":0,
                    |"numFollowers":0,
                    |"kind":"user_created",
                    |"access":"read_write"
                  |}
              | ]
            |}
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)
      }
    }

    "invite users to library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, user3, lib1) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val user2 = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1))
          val user3 = userRepo.save(User(firstName = "Char", lastName = "Mander", createdAt = t1))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          (user1, user2, user3, library)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.inviteUsersToLibrary(pubId).url
        inject[FakeUserActionsHelper].setUser(user1)

        val inputJson1 = Json.obj(
          "invites" -> Seq(
            Json.obj("type" -> "user", "id" -> user2.externalId, "access" -> LibraryAccess.READ_ONLY),
            Json.obj("type" -> "user", "id" -> user3.externalId, "access" -> LibraryAccess.READ_ONLY),
            Json.obj("type" -> "email", "id" -> "squirtle@gmail.com", "access" -> LibraryAccess.READ_ONLY))
        )
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1)
        val result1 = libraryController.inviteUsersToLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
            |[
            | {"user":"${user2.externalId}","access":"${LibraryAccess.READ_ONLY.value}"},
            | {"user":"${user3.externalId}","access":"${LibraryAccess.READ_ONLY.value}"},
            | {"email":"squirtle@gmail.com","access":"${LibraryAccess.READ_ONLY.value}"}
            |]
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)

        val inputJson2 = Json.obj(
          "message" -> "Here is another invite!",
          "invites" -> Seq(
            Json.obj("type" -> "email", "id" -> "squirtle@gmail.com", "access" -> LibraryAccess.READ_INSERT))
        )
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2)
        val result2 = libraryController.inviteUsersToLibrary(pubId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(s"""[{"email":"squirtle@gmail.com","access":"${LibraryAccess.READ_INSERT.value}"}]"""))
        db.readOnlyMaster { implicit s =>
          val invitesToSquirtle = libraryInviteRepo.getWithLibraryId(lib1.id.get).filter(i => i.emailAddress.nonEmpty)
          invitesToSquirtle.map(_.message) === Seq(None, Some("Here is another invite!"))
        }
      }
    }

    "join or decline library invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryController = inject[LibraryController]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val (user1, user2, lib1, lib2, inv1, inv2) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Some(Username("ahsu"))))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Some(Username("bulbasaur"))))

          // user B owns 2 libraries
          val libraryB1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val libraryB2 = libraryRepo.save(Library(name = "Library2", ownerId = userB.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB2.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          // user B invites A to both libraries
          val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB1.id.get, ownerId = userB.id.get, userId = Some(userA.id.get), access = LibraryAccess.READ_INSERT))
          val inv2 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB2.id.get, ownerId = userB.id.get, userId = Some(userA.id.get), access = LibraryAccess.READ_INSERT))
          (userA, userB, libraryB1, libraryB2, inv1, inv2)
        }

        val pubLibId1 = Library.publicId(lib1.id.get)
        val pubLibId2 = Library.publicId(lib2.id.get)

        val testPathJoin = com.keepit.controllers.website.routes.LibraryController.joinLibrary(pubLibId1).url
        val testPathDecline = com.keepit.controllers.website.routes.LibraryController.declineLibrary(pubLibId2).url
        inject[FakeUserActionsHelper].setUser(user1)

        val request1 = FakeRequest("POST", testPathJoin)
        val result1 = libraryController.joinLibrary(pubLibId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val basicUser2 = db.readOnlyMaster { implicit s => basicUserRepo.load(user2.id.get) }

        val expected = Json.parse(
          s"""
             |{
             |"id":"${Library.publicId(lib1.id.get).id}",
             |"name":"Library1",
             |"visibility":"discoverable",
             |"url":"/bulbasaur/lib1",
             |"owner":{
             |  "id":"${basicUser2.externalId}",
             |  "firstName":"${basicUser2.firstName}",
             |  "lastName":"${basicUser2.lastName}",
             |  "pictureName":"${basicUser2.pictureName}",
             |  "username":"${basicUser2.username.get.value}"
             |  },
             |"numKeeps":0,
             |"numFollowers":1,
             |"kind":"user_created"
             |}
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)

        val request2 = FakeRequest("POST", testPathDecline)
        val result2 = libraryController.declineLibrary(pubLibId2)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
      }
    }

    "leave library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, lib1) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1))

          // Bulba owns this library
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          // Aaron has membership to Bulba's library
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))
          (userA, userB, library1)
        }

        val pubId1 = Library.publicId(lib1.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.leaveLibrary(pubId1).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.leaveLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
      }
    }

    "get keeps" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val site1 = "http://www.google.com/"
        val site2 = "http://www.amazon.com/"
        val (user1, user2, lib1, keep1, keep2) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val userB = userRepo.save(User(firstName = "AyAyRon", lastName = "Hsu", createdAt = t1))

          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userA.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("k1"), userId = userA.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("k2"), userId = userA.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))

          (userA, userB, library1, keep1, keep2)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getKeeps(pubId1, 10, 0).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.getKeeps(pubId1, 10, 0)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
          {
            "keeps": [
              {
                "id": "${keep2.externalId}",
                "title": "k2",
                "url": "http://www.amazon.com/",
                "isPrivate": false,
                "createdAt": "${keep2.createdAt}",
                "others": -1,
                "keepers": [
                  {
                    "id": "${user1.externalId}",
                    "firstName": "Aaron",
                    "lastName": "Hsu",
                    "pictureName": "0.jpg"
                  }
                ],
                "collections": [],
                "tags": [],
                "summary": {},
                "siteName": "Amazon",
                "libraryId": "l7jlKlnA36Su"
              },
              {
                "id": "${keep1.externalId}",
                "title": "k1",
                "url": "http://www.google.com/",
                "isPrivate": false,
                "createdAt": "${keep1.createdAt}",
                "others": -1,
                "keepers": [
                  {
                    "id": "${user1.externalId}",
                    "firstName": "Aaron",
                    "lastName": "Hsu",
                    "pictureName": "0.jpg"
                  }
                ],
                "collections": [],
                "tags": [],
                "summary": {},
                "siteName": "Google",
                "libraryId": "l7jlKlnA36Su"
              }
            ],
            "count": 2,
            "offset": 0,
            "numKeeps": 2
           }
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)
      }
    }

    "copy & move keeps between libraries" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        implicit val config = inject[PublicIdConfiguration]
        val libraryController = inject[LibraryController]

        val (userA, userB, lib1, lib2, keep1, keep2) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userA.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val uri1 = uriRepo.save(NormalizedURI.withHash("http://www.google.com/", Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("http://www.amazon.com/", Some("Amazon")))
          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val keep1 = keepRepo.save(Keep(title = Some("G1"), userId = userA.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("A1"), userId = userA.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusHours(50), state = KeepStates.ACTIVE,
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))

          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = userB.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = userA.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userB.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))

          (userA, userB, library1, library2, keep1, keep2)
        }

        val testPathCopy = com.keepit.controllers.website.routes.LibraryController.copyKeeps().url
        val testPathMove = com.keepit.controllers.website.routes.LibraryController.moveKeeps().url
        inject[FakeUserActionsHelper].setUser(userA)

        val inputJsonTo2 = Json.obj(
          "to" -> Library.publicId(lib2.id.get),
          "keeps" -> Seq(keep1.externalId, keep2.externalId)
        )

        // keeps are all in library 1
        // move keeps (from Lib1 to Lib2) as user 1 (should fail)
        val request1 = FakeRequest("POST", testPathMove).withBody(inputJsonTo2)
        val result1 = libraryController.moveKeeps()(request1)
        (contentAsJson(result1) \ "failures" \\ "error").head.as[String] === "dest_permission_denied"

        inject[FakeUserActionsHelper].setUser(userB)

        // move keeps (from Lib1 to Lib2) as user 2 (ok) - keeps 1,2 in lib2
        val request2 = FakeRequest("POST", testPathMove).withBody(inputJsonTo2).withHeaders("userId" -> "2")
        val result2 = libraryController.moveKeeps()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val jsonRes2 = Json.parse(contentAsString(result2))
        (jsonRes2 \ "success").as[Boolean] === true

        inject[FakeUserActionsHelper].setUser(userA)

        // copy keeps from Lib1 to Lib2 as user 1 (should fail)
        val request3 = FakeRequest("POST", testPathCopy).withBody(inputJsonTo2)
        val result3 = libraryController.copyKeeps()(request3)
        status(result3) must equalTo(OK)

        (contentAsJson(result3) \ "success").as[Boolean] === false
        (contentAsJson(result3) \\ "error").map(_.as[String]).toSet === Set("dest_permission_denied")

        inject[FakeUserActionsHelper].setUser(userB)

        // copy keeps from Lib2 to Lib1 as user 2 (ok) - keeps 1,2 in both lib1 & lib2
        val inputJsonTo1 = Json.obj(
          "to" -> Library.publicId(lib1.id.get),
          "keeps" -> Seq(keep1.externalId, keep2.externalId)
        )
        val request4 = FakeRequest("POST", testPathCopy).withBody(inputJsonTo1).withHeaders("userId" -> "2")
        val result4 = libraryController.copyKeeps()(request4)
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")
        val jsonRes4 = Json.parse(contentAsString(result4))
        (jsonRes4 \ "success").as[Boolean] === true
        (jsonRes4 \\ "keep").length === 0
      }
    }

    "get collaborators & followers" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, user3, user4, lib) = db.readWrite { implicit s =>
          val u1 = userRepo.save(User(firstName = "Mario", lastName = "Plumber"))
          val u2 = userRepo.save(User(firstName = "Luigi", lastName = "Plumber"))
          val u3 = userRepo.save(User(firstName = "Bowser", lastName = "Koopa"))
          val u4 = userRepo.save(User(firstName = "Peach", lastName = "Princess"))

          val lib = libraryRepo.save(Library(ownerId = u1.id.get, name = "Mario Party", visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("party"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER, showInSearch = true, createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(userId = u2.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true, createdAt = t1.plusHours(1)))
          libraryMembershipRepo.save(LibraryMembership(userId = u3.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, createdAt = t1.plusHours(2)))
          libraryMembershipRepo.save(LibraryMembership(userId = u4.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, createdAt = t1.plusHours(3)))
          (u1, u2, u3, u4, lib)
        }

        inject[FakeUserActionsHelper].setUser(user1)

        val pubId1 = Library.publicId(lib.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getCollaborators(pubId1, 2, 0).url
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.getCollaborators(pubId1, 2, 0)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
             |{
               |"collaborators": [
               |  {"id":"${user2.externalId}",
               |  "firstName":"Luigi",
               |  "lastName":"Plumber",
               |  "pictureName":"0.jpg"}
               |  ],
               |"followers":[
               |  {"id":"${user3.externalId}",
               |  "firstName":"Bowser",
               |  "lastName":"Koopa",
               |  "pictureName":"0.jpg"}
               |  ],
               |"numCollaborators":1,
               |"numFollowers":2,
               |"count":2,
               |"offset":0
               |}""".stripMargin))

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.getCollaborators(pubId1, 2, 1).url
        val request2 = FakeRequest("POST", testPath2)
        val result2 = libraryController.getCollaborators(pubId1, 2, 1)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
             |{
               |"collaborators": [],
               |"followers":[
               |  {"id":"${user3.externalId}",
               |  "firstName":"Bowser",
               |  "lastName":"Koopa",
               |  "pictureName":"0.jpg"},
               |  {"id":"${user4.externalId}",
               |  "firstName":"Peach",
               |  "lastName":"Princess",
               |  "pictureName":"0.jpg"}
               |  ],
               |"numCollaborators":1,
               |"numFollowers":2,
               |"count":2,
               |"offset":1
               |}""".stripMargin))
      }
    }

    "add keeps to library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val u1 = userRepo.save(User(firstName = "Mario", lastName = "Plumber"))

          val lib1 = libraryRepo.save(Library(ownerId = u1.id.get, name = "Mario Party", visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("marioparty"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib1.id.get, access = LibraryAccess.OWNER, showInSearch = true, createdAt = t1))

          val lib2 = libraryRepo.save(Library(ownerId = u1.id.get, name = "Luigi Party", visibility = LibraryVisibility.SECRET, slug = LibrarySlug("luigiparty"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib2.id.get, access = LibraryAccess.OWNER, showInSearch = true, createdAt = t1))
          (u1, lib1, lib2)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.addKeeps(pubId1).url
        val testPath2 = com.keepit.controllers.website.routes.LibraryController.addKeeps(pubId2).url

        val keepsToAdd =
          RawBookmarkRepresentation(title = Some("title 11"), url = "http://www.hi.com11", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 21"), url = "http://www.hi.com21", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 31"), url = "http://www.hi.com31", isPrivate = None) ::
            Nil

        inject[FakeUserActionsHelper].setUser(user1)

        val json = Json.obj(
          "keeps" -> JsArray(keepsToAdd map { k => Json.toJson(k) })
        )
        val request1 = FakeRequest("POST", testPath1).withBody(json)
        val result1 = libraryController.addKeeps(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val (k1, k2, k3) = db.readOnlyMaster { implicit s =>
          val keeps = keepRepo.getByLibrary(lib1.id.get, 10, 0).sortBy(_.createdAt)
          keeps.length === 3
          (keeps(0), keeps(1), keeps(2))
        }

        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
            {
              "keeps":
              [{"id":"${k1.externalId}","title":"title 11","url":"http://www.hi.com11","isPrivate":false, "libraryId":"${pubId1.id}"},
              {"id":"${k2.externalId}","title":"title 21","url":"http://www.hi.com21","isPrivate":false, "libraryId":"${pubId1.id}"},
              {"id":"${k3.externalId}","title":"title 31","url":"http://www.hi.com31","isPrivate":false, "libraryId":"${pubId1.id}"}],
              "failures":[],
              "alreadyKept":[]
            }
          """.stripMargin
        ))

        val request2 = FakeRequest("POST", testPath2).withBody(json)
        val result2 = libraryController.addKeeps(pubId2)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val (k4, k5, k6) = db.readOnlyMaster { implicit s =>
          val keeps = keepRepo.getByLibrary(lib2.id.get, 10, 0).sortBy(_.createdAt)
          keeps.length === 3
          (keeps(0), keeps(1), keeps(2))
        }

        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            {
              "keeps":[
                {"id":"${k4.externalId}","title":"title 11","url":"http://www.hi.com11","isPrivate":true, "libraryId":"${pubId2.id}"},
                {"id":"${k5.externalId}","title":"title 21","url":"http://www.hi.com21","isPrivate":true, "libraryId":"${pubId2.id}"},
                {"id":"${k6.externalId}","title":"title 31","url":"http://www.hi.com31","isPrivate":true, "libraryId":"${pubId2.id}"}
                ],
              "failures":[],
              "alreadyKept":[]
            }
          """.stripMargin
        ))

        val request3 = FakeRequest("POST", testPath2).withBody(
          Json.obj(
            "keeps" -> Json.toJson(Seq(RawBookmarkRepresentation(title = Some("title 11zzz"), url = "http://www.hi.com11", isPrivate = None)))
          ))
        val result3 = libraryController.addKeeps(pubId2)(request3)
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")

        Json.parse(contentAsString(result3)) must equalTo(Json.parse(
          s"""
              {
                "keeps":[],
                "failures":[],
                "alreadyKept":[{"id":"${k4.externalId}","title":"title 11zzz","url":"http://www.hi.com11","isPrivate":true, "libraryId":"${pubId2.id}"}]
              }
            """.stripMargin
        ))
      }
    }

  }
}

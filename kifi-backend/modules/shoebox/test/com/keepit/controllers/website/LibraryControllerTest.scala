package com.keepit.controllers.website

import java.io.File

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders._
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }

import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.{ FakeShoeboxStoreModule, ImageSize }
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model.KeepFactory._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactory._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryMembershipFactory._
import com.keepit.model.LibraryMembershipFactoryHelper._
import com.keepit.model.UserFactory._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.search.{ FakeSearchServiceClient, FakeSearchServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social.BasicUser
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.io.FileUtils
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.Files.TemporaryFile
import play.api.libs.json._
import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeCryptoModule(),
    FakeShoeboxStoreModule(),
    FakeABookServiceClientModule(),
    FakeKeepImportsModule(),
    FakeMailModule(),
    FakeCortexServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeSocialGraphModule(),
    FakeShoeboxServiceModule()
  )

  private def fakeImage1 = {
    val tf = TemporaryFile(new File("test/data/image1-" + Math.random() + ".png"))
    tf.file.deleteOnExit()
    FileUtils.copyFile(new File("test/data/image1.png"), tf.file)
    tf
  }

  import com.keepit.commanders.RawBookmarkRepresentation._

  implicit val helperFormat = RawBookmarkRepresentation.helperFormat

  // NOTE: No attemp to write the trait SourceAttribution
  implicit val rawBookmarkRepwrites = new Writes[RawBookmarkRepresentation] {
    def writes(keep: RawBookmarkRepresentation): JsValue = {
      val tmp = RawBookmarkRepresentationWithoutAttribution(keep.title, keep.url, keep.isPrivate, keep.canonical, keep.openGraph, keep.keptAt, keep.note)
      Json.toJson(tmp)
    }
  }

  "LibraryController" should {

    "create library" in {
      withDb(modules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]
        val testPath = com.keepit.controllers.website.routes.LibraryController.addLibrary().url
        val user = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
        }
        val inputJson1 = Json.obj(
          "name" -> "Library1",
          "slug" -> "lib1",
          "visibility" -> "secret"
        )
        inject[FakeUserActionsHelper].setUser(user)
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1)
        val result1 = libraryController.addLibrary()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val parseLibrary = (contentAsJson(result1) \ "library")
        (parseLibrary \ "name").as[String] === "Library1"
        (parseLibrary \ "slug").as[LibrarySlug].value === "lib1"
        (parseLibrary \ "visibility").as[LibraryVisibility].value === "secret"
        (parseLibrary \ "keeps").as[Seq[JsValue]].size === 0
        (parseLibrary \ "owner").as[BasicUser].externalId === user.externalId
        (contentAsJson(result1) \ "listed").asOpt[Boolean].get === true

        val inputJson2 = Json.obj(
          "name" -> "Invalid Library - Slug",
          "slug" -> "lib2 abcd",
          "visibility" -> "secret"
        )
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2)
        val result2 = libraryController.addLibrary()(request2)
        status(result2) must equalTo(BAD_REQUEST)
        contentType(result2) must beSome("application/json")

        // Re-add Library 1 (same name)
        val request3 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(Json.obj(
          "name" -> "Library1",
          "slug" -> "libA",
          "visibility" -> "secret"
        ))
        val result3 = libraryController.addLibrary()(request3)
        status(result3) must equalTo(BAD_REQUEST)
        Json.parse(contentAsString(result3)) === Json.parse(s"""{"error":"library_name_exists"}""")

        // Re-add Library 1 (same slug)
        val request4 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(Json.obj(
          "name" -> "LibraryA",
          "slug" -> "lib1",
          "visibility" -> "secret"
        ))
        val result4 = libraryController.addLibrary()(request4)
        status(result4) must equalTo(BAD_REQUEST)
        Json.parse(contentAsString(result4)) === Json.parse(s"""{"error":"library_slug_exists"}""")

        // Add Library with invalid name
        val inputJson5 = Json.obj(
          "name" -> "Invalid Name - \"",
          "slug" -> "lib5",
          "visibility" -> "secret"
        )
        val request5 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(inputJson5)
        val result5 = libraryController.addLibrary()(request5)
        status(result5) must equalTo(BAD_REQUEST)
        contentType(result5) must beSome("application/json")
      }
    }

    "modify library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("ahsu"), normalizedUsername = "test"))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user.id.get, access = LibraryAccess.OWNER))

          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user.id.get, access = LibraryAccess.OWNER))
          (user, library1, library2)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.modifyLibrary(pubId).url
        inject[FakeUserActionsHelper].setUser(user1)

        val inputJson1 = Json.obj("name" -> "LibraryA")
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1)
        val result1 = libraryController.modifyLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        (contentAsJson(result1) \ "library" \ "name").as[String] === "LibraryA"

        val inputJson2 = Json.obj("slug" -> "libA", "description" -> "asdf", "visibility" -> LibraryVisibility.PUBLISHED, "listed" -> true, "color" -> "sky_blue")
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2)
        val result2 = libraryController.modifyLibrary(pubId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val basicUser1 = db.readOnlyMaster { implicit s =>
          basicUserRepo.load(user1.id.get)
        }
        val expected = Json.parse(
          s"""
            {
              "library": {
                "id":"${pubId.id}",
                "name":"LibraryA",
                "visibility":"published",
                "shortDescription":"asdf",
                "url":"/ahsu/libA",
                "color":"${LibraryColor.SKY_BLUE.hex}",
                "owner":{
                  "id":"${basicUser1.externalId}",
                  "firstName":"${basicUser1.firstName}",
                  "lastName":"${basicUser1.lastName}",
                  "pictureName":"${basicUser1.pictureName}",
                  "username":"${basicUser1.username.value}"
                  },
                "numKeeps":0,
                "numFollowers":0,
                "kind":"user_created"
              },
              "listed": true
            }
           """)
        Json.parse(contentAsString(result2)) must equalTo(expected)

        // modify to existing library name
        val request3 = FakeRequest("POST", testPath).withBody(Json.obj("name" -> "Library2"))
        val result3 = libraryController.modifyLibrary(pubId)(request3)
        status(result3) must equalTo(BAD_REQUEST)
        Json.parse(contentAsString(result3)) === Json.parse(s"""{"error":"library_name_exists"}""")

        // modify to existing library slug
        val request4 = FakeRequest("POST", testPath).withBody(Json.obj("slug" -> "lib2"))
        val result4 = libraryController.modifyLibrary(pubId)(request4)
        status(result4) must equalTo(BAD_REQUEST)
        Json.parse(contentAsString(result4)) === Json.parse(s"""{"error":"library_slug_exists"}""")

      }
    }

    "remove library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "Someone", lastName = "Else", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))
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
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("ahsu"), normalizedUsername = "test"))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = library.id.get, access = LibraryAccess.OWNER))
          (user1, library)
        }

        val pubId1 = Library.publicId(lib1.id.get)

        // upload an image
        {
          val savedF = inject[LibraryImageCommander].uploadLibraryImageFromFile(fakeImage1, lib1.id.get, LibraryImagePosition(None, None), ImageSource.UserUpload, user1.id.get)(HeimdalContext.empty)
          val saved = Await.result(savedF, Duration("10 seconds"))
          saved === ImageProcessState.StoreSuccess(ImageFormat.PNG, ImageSize(66, 38), 612)
        }

        val testPath = com.keepit.controllers.website.routes.LibraryController.getLibraryById(pubId1).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("GET", testPath)
        val result1 = libraryController.getLibraryById(pubId1, false)(request1)
        val lib1Updated = db.readOnlyMaster { libraryRepo.get(lib1.id.get)(_) }
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val basicUser1 = db.readOnlyMaster { implicit s => basicUserRepo.load(user1.id.get) }
        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""{
           "library":{
             "id":"${pubId1.id}",
             "name":"Library1",
             "visibility":"secret",
             "slug":"lib1",
             "url":"/ahsu/lib1",
             "image":{
                  "path":"library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png",
                  "x":50,
                  "y":50
                },
             "kind":"user_created",
             "owner":{
               "id":"${basicUser1.externalId}",
               "firstName":"${basicUser1.firstName}",
               "lastName":"${basicUser1.lastName}",
               "pictureName":"${basicUser1.pictureName}",
               "username":"${basicUser1.username.value}"
               },
             "followers":[],
             "collaborators":[],
             "keeps":[],
             "numKeeps":0,
             "numCollaborators":0,
             "numFollowers":0,
             "whoCanInvite":"collaborator",
             "modifiedAt": ${lib1Updated.updatedAt.getMillis},
             "membership": {
              "access" : "owner",
              "listed":true,
              "subscribed":false
             },
             "invite": null
           },
           "suggestedSearches": {"terms": [], "weights": []}
          }
        """))

        // viewed by another user with an invite
        val user2 = db.readWrite { implicit s =>
          val user2 = userRepo.save(User(firstName = "Baron", lastName = "Hsu", createdAt = t1, username = Username("bhsu"), normalizedUsername = "test"))
          libraryInviteRepo.save(LibraryInvite(libraryId = lib1.id.get, inviterId = user1.id.get, userId = user2.id, access = LibraryAccess.READ_ONLY, authToken = "abc", createdAt = t1.plusMinutes(3)))
          user2
        }
        inject[FakeUserActionsHelper].setUser(user2)
        val request2 = FakeRequest("GET", testPath)
        val result2 = libraryController.getLibraryById(pubId1, false)(request2)
        val lib1Updated2 = db.readOnlyMaster { libraryRepo.get(lib1.id.get)(_) }

        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""{
           "library":{
             "id":"${pubId1.id}",
             "name":"Library1",
             "visibility":"secret",
             "slug":"lib1",
             "url":"/ahsu/lib1",
             "image":{
                  "path":"library/26dbdc56d54dbc94830f7cfc85031481_66x38_o.png",
                  "x":50,
                  "y":50
                },
             "kind":"user_created",
             "owner":{
               "id":"${basicUser1.externalId}",
               "firstName":"${basicUser1.firstName}",
               "lastName":"${basicUser1.lastName}",
               "pictureName":"${basicUser1.pictureName}",
               "username":"${basicUser1.username.value}"
             },
             "followers":[],
             "collaborators":[],
             "keeps":[],
             "numKeeps":0,
             "numCollaborators":0,
             "numFollowers":0,
             "whoCanInvite":"collaborator",
             "modifiedAt": ${lib1Updated2.updatedAt.getMillis},
             "membership":null,
             "invite": {
              "inviter": {
                "id":"${basicUser1.externalId}",
                "firstName":"${basicUser1.firstName}",
                "lastName":"${basicUser1.lastName}",
                "pictureName":"${basicUser1.pictureName}",
                "username":"${basicUser1.username.value}"
              },
              "access":"read_only",
              "lastInvite":${t1.plusMinutes(3).getMillis}
             }
           },
           "suggestedSearches": {"terms": [], "weights": []}
          }
        """))
      }
    }

    "get library by path" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("ahsu"), normalizedUsername = UsernameOps.normalize("ahsu")))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(userId = user.id.get, libraryId = library.id.get, access = LibraryAccess.OWNER, listed = false))
          (user, library)
        }

        val unInput = user1.username.value
        val badUserInput = "ahsuifhwoifhweof"
        val extInput = user1.externalId.id
        val slugInput = lib1.slug.value
        inject[FakeUserActionsHelper].setUser(user1)

        db.readOnlyMaster { implicit s =>
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get.lastViewed.isDefined === false
        }

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(unInput, slugInput).url
        val request1 = FakeRequest("GET", testPath1)
        val result1 = libraryController.getLibraryByPath(unInput, slugInput, false)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val firstTime = db.readOnlyMaster { implicit s =>
          val mem = libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get
          mem.lastViewed.isDefined === true
          mem.lastViewed.get
        }

        val testPath1_bad = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(badUserInput, slugInput).url
        val request1_bad = FakeRequest("GET", testPath1_bad)
        val result1_bad = libraryController.getLibraryByPath(badUserInput, slugInput, false)(request1_bad)
        status(result1_bad) must equalTo(BAD_REQUEST)

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.getLibraryByPath(extInput, slugInput).url
        val request2 = FakeRequest("GET", testPath2)
        val result2 = libraryController.getLibraryByPath(extInput, slugInput, false)(request2)
        val lib1Updated = db.readOnlyMaster { libraryRepo.get(lib1.id.get)(_) }
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
             "library":{
               "id":"${Library.publicId(lib1.id.get).id}",
               "name":"Library1",
               "visibility":"secret",
               "slug":"lib1",
               "url":"/ahsu/lib1",
               "kind":"user_created",
               "owner":{
                 "id":"${basicUser1.externalId}",
                 "firstName":"${basicUser1.firstName}",
                 "lastName":"${basicUser1.lastName}",
                 "pictureName":"${basicUser1.pictureName}",
                 "username":"${basicUser1.username.value}"
                 },
               "followers":[],
               "collaborators":[],
               "keeps":[],
               "numKeeps":0,
               "numCollaborators":0,
               "numFollowers":0,
               "whoCanInvite":"collaborator",
               "modifiedAt": ${lib1Updated.updatedAt.getMillis},
               "membership":{
                "access":"owner",
                "listed":false,
                "subscribed":false
               },
               "invite": null
             },
             "suggestedSearches": {"terms": [], "weights": []}
            }
          """)
        Json.parse(contentAsString(result1)) must equalTo(expected)
        Json.parse(contentAsString(result2)) must equalTo(expected)
      }
    }

    "get libraries of user" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2014, 10, 1, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, lib1, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "A", createdAt = t1, username = Username("ahsu"), normalizedUsername = "test"))
          val user2 = userRepo.save(User(firstName = "Baron", lastName = "B", createdAt = t1, username = Username("bhsu"), normalizedUsername = "test"))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.PUBLISHED))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, lastViewed = Some(t2)))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))

          val library3 = libraryRepo.save(Library(name = "Library3", ownerId = user2.id.get, slug = LibrarySlug("lib3"), memberCount = 2, visibility = LibraryVisibility.DISCOVERABLE))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER))
          libraryInviteRepo.save(LibraryInvite(libraryId = library3.id.get, inviterId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_ONLY, state = LibraryInviteStates.ACCEPTED))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY))

          // send invites to same library with different access levels (only want highest access level)
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, inviterId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_ONLY))
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, inviterId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_INSERT))
          libraryInviteRepo.save(LibraryInvite(libraryId = library2.id.get, inviterId = user2.id.get, userId = user1.id, access = LibraryAccess.READ_WRITE, state = LibraryInviteStates.DECLINED))
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
                  |  "username":"${basicUser1.username.value}"
                  |  },
                  |"numKeeps":0,
                  |"numFollowers":0,
                  |"kind":"user_created",
                  |"lastViewed":${Json.toJson(t2)(internalTime.DateTimeJsonLongFormat)}
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
                    |  "username":"${basicUser2.username.value}"
                    |  },
                    |"numKeeps":0,
                    |"numFollowers":0,
                    |"kind":"user_created",
                    |"inviter":{"id":"${user2.externalId}","firstName":"Baron","lastName":"B","pictureName":"0.jpg","username":"bhsu"}
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

        val (user1, user2, user3, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test", primaryEmail = Some(EmailAddress("aaron@kifi.com"))))
          val user2 = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Username("test"), normalizedUsername = "test", primaryEmail = Some(EmailAddress("bulba@yahoo.com"))))
          val user3 = userRepo.save(User(firstName = "Char", lastName = "Mander", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user1.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          (user1, user2, user3, library1, library2)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.inviteUsersToLibrary(pubId1).url
        val testPath2 = com.keepit.controllers.website.routes.LibraryController.inviteUsersToLibrary(pubId2).url
        inject[FakeUserActionsHelper].setUser(user1)

        val inputJson1 = Json.obj(
          "invites" -> Seq(
            Json.obj("type" -> "user", "id" -> user2.externalId, "access" -> LibraryAccess.READ_WRITE),
            Json.obj("type" -> "user", "id" -> user3.externalId, "access" -> LibraryAccess.READ_ONLY),
            Json.obj("type" -> "email", "id" -> "squirtle@gmail.com", "access" -> LibraryAccess.READ_ONLY))
        )
        val request1 = FakeRequest("POST", testPath1).withBody(inputJson1)
        val result1 = libraryController.inviteUsersToLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
            |[
            | {"user":"${user2.externalId}","access":"${LibraryAccess.READ_WRITE.value}"},
            | {"user":"${user3.externalId}","access":"${LibraryAccess.READ_ONLY.value}"},
            | {"email":"squirtle@gmail.com","access":"${LibraryAccess.READ_ONLY.value}"}
            |]
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)

        val inputJson2 = Json.obj(
          "message" -> "Here is another invite!",
          "invites" -> Seq(
            Json.obj("type" -> "email", "id" -> "squirtle@gmail.com", "access" -> LibraryAccess.READ_ONLY))
        )
        val request2 = FakeRequest("POST", testPath1).withBody(inputJson2)
        val result2 = libraryController.inviteUsersToLibrary(pubId1)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(s"""[{"email":"squirtle@gmail.com","access":"${LibraryAccess.READ_ONLY.value}"}]"""))
        db.readOnlyMaster { implicit s =>
          val invitesToSquirtle = libraryInviteRepo.getWithLibraryId(lib1.id.get).filter(i => i.emailAddress.nonEmpty)
          invitesToSquirtle.map(_.message) === Seq(None) // second invite doesn't persist because it was sent too close to previous one
        }

        inject[FakeUserActionsHelper].setUser(user2)
        // success sharing a SECRET library that you don't own
        val request3 = FakeRequest("POST", testPath1).withBody(inputJson2)
        val result3 = libraryController.inviteUsersToLibrary(pubId1)(request3)
        status(result3) must equalTo(OK)
        // success sharing a PUBLISHED library that you don't own
        val request4 = FakeRequest("POST", testPath2).withBody(inputJson2)
        val result4 = libraryController.inviteUsersToLibrary(pubId2)(request4)
        status(result4) must equalTo(OK)
      }
    }

    "uninvite user" in {

      def setupUninvite()(implicit injector: Injector): (User, User, Library, LibraryInvite) = {
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("ahsu"), normalizedUsername = "test", primaryEmail = Some(EmailAddress("email@gmail.com"))))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Username("bulbasaur"), normalizedUsername = "test"))

          val libraryB1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER))

          val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB1.id.get, inviterId = userB.id.get, userId = Some(userA.id.get), access = LibraryAccess.READ_INSERT))
          (userA, userB, libraryB1, inv1)
        }
      }

      "succeed when inviter tries to uninvite" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val libraryController = inject[LibraryController]
          val (invitee: User, inviter: User, library: Library, invite: LibraryInvite) = setupUninvite()
          val libraryId = Library.publicId(library.id.get)

          inject[FakeUserActionsHelper].setUser(inviter)
          val uninvitePath = com.keepit.controllers.website.routes.LibraryController.revokeLibraryInvitation(libraryId).url
          val uninviteJson = Json.obj(
            "type" -> "user",
            "invitee" -> s"${invitee.externalId}"
          )
          val request = FakeRequest("DELETE", uninvitePath).withBody(uninviteJson)

          val result = libraryController.revokeLibraryInvitation(libraryId)(request)
          status(result) must equalTo(NO_CONTENT)
          contentType(result) must beNone
          db.readOnlyMaster { implicit s =>
            libraryInviteRepo.getLastSentByLibraryIdAndInviterIdAndUserId(library.id.get, inviter.id.get, invitee.id.get, Set(LibraryInviteStates.INACTIVE)) must beSome
          }
        }
      }
      "fail when someone else tries to uninvite" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val libraryController = inject[LibraryController]
          val (invitee: User, inviter: User, library: Library, invite: LibraryInvite) = setupUninvite()
          val libraryId = Library.publicId(library.id.get)

          inject[FakeUserActionsHelper].setUser(invitee)
          val uninvitePath = com.keepit.controllers.website.routes.LibraryController.revokeLibraryInvitation(libraryId).url
          val uninviteJson = Json.obj(
            "type" -> "user",
            "invitee" -> s"${invitee.externalId}"
          )

          val request = FakeRequest("DELETE", uninvitePath).withBody(uninviteJson)
          val result = libraryController.revokeLibraryInvitation(libraryId)(request)
          status(result) must equalTo(BAD_REQUEST)
          contentType(result) must beSome("application/json")
          contentAsString(result) must equalTo("{\"error\":\"library_invite_not_found\"}")
        }
      }

      "fail for invalid library id" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val libraryController = inject[LibraryController]
          val (invitee: User, inviter: User, library: Library, invite: LibraryInvite) = setupUninvite()
          val libraryId = PublicId[Library]("x")

          inject[FakeUserActionsHelper].setUser(inviter)
          val uninvitePath = com.keepit.controllers.website.routes.LibraryController.revokeLibraryInvitation(libraryId).url
          val uninviteJson = Json.obj(
            "type" -> "user",
            "invitee" -> s"${invitee.externalId}"
          )
          val request = FakeRequest("DELETE", uninvitePath).withBody(uninviteJson)

          val result = libraryController.revokeLibraryInvitation(libraryId)(request)
          status(result) must equalTo(BAD_REQUEST)
          contentType(result) must beSome("application/json")
          contentAsString(result) must equalTo("{\"error\":\"invalid_library_id\"}")
        }
      }

      "fail for invalid user type" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val libraryController = inject[LibraryController]
          val (invitee: User, inviter: User, library: Library, invite: LibraryInvite) = setupUninvite()
          val libraryId = Library.publicId(library.id.get)

          inject[FakeUserActionsHelper].setUser(inviter)
          val uninvitePath = com.keepit.controllers.website.routes.LibraryController.revokeLibraryInvitation(libraryId).url
          val uninviteJson = Json.obj(
            "type" -> "x",
            "invitee" -> "email@gmail.com"
          )
          val request = FakeRequest("DELETE", uninvitePath).withBody(uninviteJson)

          val result = libraryController.revokeLibraryInvitation(libraryId)(request)
          status(result) must equalTo(BAD_REQUEST)
          contentType(result) must beSome("application/json")
          contentAsString(result) must equalTo("{\"error\":\"invalid_invitee_type\"}")
        }
      }

      "fail for invalid user externalId" in {
        withDb(modules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val libraryController = inject[LibraryController]
          val (invitee: User, inviter: User, library: Library, invite: LibraryInvite) = setupUninvite()
          val libraryId = Library.publicId(library.id.get)

          inject[FakeUserActionsHelper].setUser(inviter)
          val uninvitePath = com.keepit.controllers.website.routes.LibraryController.revokeLibraryInvitation(libraryId).url
          val uninviteJson = Json.obj(
            "type" -> "user",
            "invitee" -> "00000000-0000-0000-0000-000000000000"
          )
          val request = FakeRequest("DELETE", uninvitePath).withBody(uninviteJson)

          val result = libraryController.revokeLibraryInvitation(libraryId)(request)
          status(result) must equalTo(BAD_REQUEST)
          contentType(result) must beSome("application/json")
          contentAsString(result) must equalTo("{\"error\":\"external_id_does_not_exist\"}")
        }
      }
    }

    "join or decline library invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryController = inject[LibraryController]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val (user1, user2, lib1, lib2, inv1, inv2) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("ahsu"), normalizedUsername = "test"))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Username("bulbasaur"), normalizedUsername = "test"))

          // user B owns 2 libraries
          val libraryB1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER))
          val libraryB2 = libraryRepo.save(Library(name = "Library2", ownerId = userB.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB2.id.get, userId = userB.id.get, access = LibraryAccess.OWNER))

          // user B invites A to both libraries
          val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB1.id.get, inviterId = userB.id.get, userId = Some(userA.id.get), access = LibraryAccess.READ_INSERT))
          val inv2 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB2.id.get, inviterId = userB.id.get, userId = Some(userA.id.get), access = LibraryAccess.READ_INSERT))
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

        val expected1 = Json.parse("""{"membership": {"access": "read_insert", "listed": true, "subscribed": false}}""")
        Json.parse(contentAsString(result1)) must equalTo(expected1)

        val result11 = libraryController.joinLibrary(pubLibId1, None, true)(request1)
        status(result11) must equalTo(OK)
        contentType(result11) must beSome("application/json")

        val expected11 = Json.parse("""{"membership": {"access": "read_insert", "listed": true, "subscribed": true}}""")
        Json.parse(contentAsString(result11)) must equalTo(expected11)

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
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          // Bulba owns this library
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER))

          // Aaron has membership to Bulba's library
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.READ_WRITE))
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
        val (user1, lib1, keep1, keep2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test"))

          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1, keepCount = 2))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("k1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))
          val keep2 = keepRepo.save(Keep(title = Some("k2"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), keptAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(library1.id.get), inDisjointLib = library1.isDisjoint))

          (user1, library1, keep1, keep2)
        }

        inject[FakeSearchServiceClient].setKeepers((Seq(keep1.userId), 1), (Seq(keep2.userId), 1))

        val pubId1 = Library.publicId(lib1.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getKeeps(pubId1, 0, 10).url
        inject[FakeUserActionsHelper].setUser(user1)
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.getKeeps(pubId1, 0, 10, false)(request1)
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
                "user":{"id":"${user1.externalId}","firstName":"Aaron","lastName":"Hsu","pictureName":"0.jpg","username":"test"},
                "createdAt": "${keep2.createdAt}",
                "keeps":[{"id":"${keep2.externalId}", "mine":true, "removable":true, "visibility":"${keep2.visibility.value}", "libraryId":"l7jlKlnA36Su"}],
                "keepers":[],
                "keepersOmitted": 0,
                "keepersTotal": 1,
                "libraries":[],
                "librariesOmitted": 0,
                "librariesTotal": 0,
                "collections": [],
                "tags": [],
                "hashtags":[],
                "summary": {},
                "siteName": "Amazon",
                "libraryId": "l7jlKlnA36Su"
              },
              {
                "id": "${keep1.externalId}",
                "title": "k1",
                "url": "http://www.google.com/",
                "isPrivate": false,
                "user":{"id":"${user1.externalId}","firstName":"Aaron","lastName":"Hsu","pictureName":"0.jpg","username":"test"},
                "createdAt": "${keep1.createdAt}",
                "keeps":[{"id":"${keep1.externalId}", "mine":true, "removable":true, "visibility":"${keep1.visibility.value}", "libraryId":"l7jlKlnA36Su"}],
                "keepers":[],
                "keepersOmitted": 0,
                "keepersTotal": 1,
                "libraries":[],
                "librariesOmitted": 0,
                "librariesTotal": 0,
                "collections": [],
                "tags": [],
                "hashtags":[],
                "summary": {},
                "siteName": "Google",
                "libraryId": "l7jlKlnA36Su"
              }
            ],
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
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userA.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER))

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

          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1, username = Username("test"), normalizedUsername = "test"))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = userB.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = userB.id.get, access = LibraryAccess.OWNER))

          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = userA.id.get, access = LibraryAccess.READ_ONLY))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userB.id.get, access = LibraryAccess.READ_WRITE))

          (userA, userB, library1, library2, keep1, keep2)
        }

        val testPathCopy = com.keepit.controllers.website.routes.LibraryController.copyKeeps().url
        val testPathMove = com.keepit.controllers.website.routes.LibraryController.moveKeeps().url
        inject[FakeUserActionsHelper].setUser(userA)

        val pubId1 = Library.publicId(lib1.id.get).id
        val pubId2 = Library.publicId(lib2.id.get).id
        val inputJson1 = Json.obj(
          "to" -> pubId2,
          "keeps" -> Seq(keep1.externalId, keep2.externalId)
        )

        // keeps are all in library 1
        // move keeps (from Lib1 to Lib2) as user 1 (should fail)
        val request1 = FakeRequest("POST", testPathMove).withBody(inputJson1)
        val result1 = libraryController.moveKeeps()(request1)
        (contentAsJson(result1) \ "failures" \\ "error").head.as[String] === "dest_permission_denied"

        inject[FakeUserActionsHelper].setUser(userB)

        // move keeps (from Lib1 to Lib2) as user 2 (ok) - keeps 1,2 in lib2
        val request2 = FakeRequest("POST", testPathMove).withBody(inputJson1).withHeaders("userId" -> "2")
        val result2 = libraryController.moveKeeps()(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            |{
            | "successes":[
            |   {
            |     "library":"${pubId2}",
            |     "numMoved": 2
            |   }
            | ]
            |}
          """.stripMargin))

        inject[FakeUserActionsHelper].setUser(userA)

        // copy keeps from Lib1 to Lib2 as user 1 (should fail)
        val request3 = FakeRequest("POST", testPathCopy).withBody(inputJson1)
        val result3 = libraryController.copyKeeps()(request3)
        status(result3) must equalTo(OK)

        (contentAsJson(result3) \ "successes").as[Seq[JsObject]].length === 0
        (contentAsJson(result3) \\ "error").map(_.as[String]).toSet === Set("dest_permission_denied")

        inject[FakeUserActionsHelper].setUser(userB)

        // copy keeps from Lib2 to Lib1 as user 2 (ok) - keeps 1,2 in both lib1 & lib2
        val inputJson2 = Json.obj(
          "to" -> Library.publicId(lib1.id.get),
          "keeps" -> Seq(keep1.externalId, keep2.externalId)
        )
        val request4 = FakeRequest("POST", testPathCopy).withBody(inputJson2).withHeaders("userId" -> "2")
        val result4 = libraryController.copyKeeps()(request4)
        status(result4) must equalTo(OK)
        contentType(result4) must beSome("application/json")
        val jsonRes4 = Json.parse(contentAsString(result4))
        val copiedKeeps = db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(lib1.id.get, 0, Int.MaxValue)
        }
        val success4 = (jsonRes4 \\ "id").map(_.as[ExternalId[Keep]]).toSet === copiedKeeps.map(_.externalId).toSet
        (jsonRes4 \\ "keep").length === 0

        // move duplicate active keeps 1 & 2 from Lib1 to Lib2 as user 2 (error: already exists in dst)
        val inputJson3 = Json.obj(
          "to" -> Library.publicId(lib1.id.get),
          "keeps" -> Seq(keep1.externalId, keep2.externalId)
        )
        val request5 = FakeRequest("POST", testPathMove).withBody(inputJson3).withHeaders("userId" -> "2")
        val result5 = libraryController.moveKeeps()(request5)
        status(result5) must equalTo(OK)
        contentType(result5) must beSome("application/json")
        val jsonRes5 = Json.parse(contentAsString(result5))
        (jsonRes5 \ "successes").as[Seq[JsObject]].length === 0
        (contentAsJson(result5) \\ "error").map(_.as[String]).toSet === Set("already_exists_in_dest")
      }
    }

    "get members" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, user3, user4, lib) = db.readWrite { implicit s =>
          val u1 = userRepo.save(User(firstName = "Mario", lastName = "Plumber", username = Username("test"), normalizedUsername = "test"))
          val u2 = userRepo.save(User(firstName = "Luigi", lastName = "Plumber", username = Username("test"), normalizedUsername = "test"))
          val u3 = userRepo.save(User(firstName = "Bowser", lastName = "Koopa", username = Username("test"), normalizedUsername = "test"))
          val u4 = userRepo.save(User(firstName = "Peach", lastName = "Princess", username = Username("test"), normalizedUsername = "test"))

          val lib = libraryRepo.save(Library(ownerId = u1.id.get, name = "Mario Party", visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("party"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib.id.get, access = LibraryAccess.OWNER, createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(userId = u2.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_WRITE, createdAt = t1.plusHours(1)))
          libraryMembershipRepo.save(LibraryMembership(userId = u3.id.get, libraryId = lib.id.get, access = LibraryAccess.READ_ONLY, createdAt = t1.plusHours(2)))
          libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = u1.id.get, userId = Some(u4.id.get), access = LibraryAccess.READ_ONLY, authToken = "abc", createdAt = t1.plusHours(3)))
          libraryInviteRepo.save(LibraryInvite(libraryId = lib.id.get, inviterId = u1.id.get, emailAddress = Some(EmailAddress("sonic@sega.co.jp")), access = LibraryAccess.READ_ONLY, authToken = "abc", createdAt = t1.plusHours(3)))
          (u1, u2, u3, u4, lib)
        }

        inject[FakeUserActionsHelper].setUser(user1)

        val pubId1 = Library.publicId(lib.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getLibraryMembers(pubId1, 0, 1).url
        val request1 = FakeRequest("POST", testPath1)
        val result1 = libraryController.getLibraryMembers(pubId1, 0, 1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
             |{
               |"members":[
               |  {"id":"${user2.externalId}",
               |  "firstName":"Luigi",
               |  "lastName":"Plumber",
               |  "pictureName":"0.jpg","username":"test",
               |  "membership":"read_write"}
               |]
             |}""".stripMargin))

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.getLibraryMembers(pubId1, 1, 4).url
        val request2 = FakeRequest("POST", testPath2)
        val result2 = libraryController.getLibraryMembers(pubId1, 1, 3)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
             |{
               |"members":[
               |  {"id":"${user3.externalId}",
               |  "firstName":"Bowser",
               |  "lastName":"Koopa",
               |  "pictureName":"0.jpg","username":"test",
               |  "membership":"read_only"},
               |  {"id":"${user4.externalId}",
               |  "firstName":"Peach",
               |  "lastName":"Princess",
               |  "pictureName":"0.jpg","username":"test",
               |  "membership":"read_only",
               |  "lastInvitedAt":${Json.toJson(t1.plusHours(3))(internalTime.DateTimeJsonLongFormat)}},
               |  {"email":"sonic@sega.co.jp",
               |  "membership":"read_only",
               |  "lastInvitedAt":${Json.toJson(t1.plusHours(3))(internalTime.DateTimeJsonLongFormat)}}
               |]
             |}""".stripMargin))

        inject[FakeUserActionsHelper].setUser(user2)
        val testPath3 = com.keepit.controllers.website.routes.LibraryController.getLibraryMembers(pubId1, 1, 4).url
        val request3 = FakeRequest("POST", testPath3)
        val result3 = libraryController.getLibraryMembers(pubId1, 1, 3)(request3)
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")

        Json.parse(contentAsString(result3)) must equalTo(Json.parse(
          s"""
             |{
               |"members":[
               |  {"id":"${user3.externalId}",
               |  "firstName":"Bowser",
               |  "lastName":"Koopa",
               |  "pictureName":"0.jpg","username":"test",
               |  "membership":"read_only"}
               |]
             |}""".stripMargin))

      }
    }

    "add keeps to library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1, lib2) = db.readWrite { implicit s =>
          val u1 = userRepo.save(User(firstName = "Mario", lastName = "Plumber", username = Username("test"), normalizedUsername = "test"))

          val lib1 = libraryRepo.save(Library(ownerId = u1.id.get, name = "Mario Party", visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("marioparty"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib1.id.get, access = LibraryAccess.OWNER, createdAt = t1))

          val lib2 = libraryRepo.save(Library(ownerId = u1.id.get, name = "Luigi Party", visibility = LibraryVisibility.SECRET, slug = LibrarySlug("luigiparty"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib2.id.get, access = LibraryAccess.OWNER, createdAt = t1))
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
          val keeps = keepRepo.getByLibrary(lib1.id.get, 0, 10).sortBy(_.createdAt)
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
          val keeps = keepRepo.getByLibrary(lib2.id.get, 0, 10).sortBy(_.createdAt)
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

    "remove keeps from library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1) = db.readWrite { implicit s =>
          val u1 = userRepo.save(User(firstName = "Mario", lastName = "Plumber", username = Username("test"), normalizedUsername = "test"))
          val lib1 = libraryRepo.save(Library(ownerId = u1.id.get, name = "Mario Party", visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("marioparty"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = u1.id.get, libraryId = lib1.id.get, access = LibraryAccess.OWNER, createdAt = t1))
          (u1, lib1)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val testPathAdd = com.keepit.controllers.website.routes.LibraryController.addKeeps(pubId1).url

        val keepsToAdd =
          RawBookmarkRepresentation(title = Some("title 11"), url = "http://www.hi.com11", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 21"), url = "http://www.hi.com21", isPrivate = None) ::
            RawBookmarkRepresentation(title = Some("title 31"), url = "http://www.hi.com31", isPrivate = None) ::
            Nil

        inject[FakeUserActionsHelper].setUser(user1)

        val json = Json.obj("keeps" -> keepsToAdd)
        val request1 = FakeRequest("POST", testPathAdd).withBody(json)
        val result1 = libraryController.addKeeps(pubId1)(request1)
        status(result1) must equalTo(OK)

        val (k1, k2, k3) = db.readOnlyMaster { implicit s =>
          val keeps = keepRepo.getByLibrary(lib1.id.get, 0, 10).sortBy(_.createdAt)
          keeps.length === 3
          (keeps(0), keeps(1), keeps(2))
        }

        // test bulk unkeeping
        val testPathRemoveMany = com.keepit.controllers.website.routes.LibraryController.removeKeeps(pubId1).url
        val k4Id: ExternalId[Keep] = ExternalId()
        val json2 = Json.obj("ids" -> Json.toJson(Seq(k1.externalId, k2.externalId, k4Id)))
        val request2 = FakeRequest("POST", testPathRemoveMany).withBody(json2)
        val result2 = libraryController.removeKeeps(pubId1)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            {
              "failures":["${k4Id}"],
              "unkept":
              [{"id":"${k1.externalId}","title":"title 11","url":"http://www.hi.com11","isPrivate":false, "libraryId":"${pubId1.id}"},
              {"id":"${k2.externalId}","title":"title 21","url":"http://www.hi.com21","isPrivate":false, "libraryId":"${pubId1.id}"}]
            }
          """.stripMargin
        ))

        // test single unkeeping
        val testPathRemoveOne = com.keepit.controllers.website.routes.LibraryController.removeKeep(pubId1, k3.externalId).url
        val request3 = FakeRequest("DELETE", testPathRemoveOne)
        val result3 = libraryController.removeKeep(pubId1, k3.externalId)(request3)
        status(result3) must equalTo(OK)
        contentType(result3) must beSome("application/json")

        Json.parse(contentAsString(result3)) must equalTo(Json.parse(
          s"""
            {
              "unkept": {"id":"${k3.externalId}","title":"title 31","url":"http://www.hi.com31","isPrivate":false, "libraryId":"${pubId1.id}"}
            }
          """.stripMargin
        ))
      }
    }

    "update keep in library" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val site1 = "http://www.spiders.com/"
        val (user1, lib1, keep1) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Peter", lastName = "Parker", username = Username("spiderman"), normalizedUsername = "spiderman"))
          val lib1 = libraryRepo.save(Library(ownerId = user1.id.get, name = "spidey stuff", visibility = LibraryVisibility.PUBLISHED,
            slug = LibrarySlug("spidey"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(userId = user1.id.get, libraryId = lib1.id.get, access = LibraryAccess.OWNER))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("spiders")))
          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val keep1 = keepRepo.save(Keep(title = Some("k1"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = lib1.visibility, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint))

          keepRepo.getByLibrary(lib1.id.get, 0, 5).map(_.title) === Seq(Some("k1"))
          (user1, lib1, keep1)
        }

        inject[FakeUserActionsHelper].setUser(user1)

        val pubId1 = Library.publicId(lib1.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.updateKeep(pubId1, keep1.externalId).url
        val inputJson1 = Json.obj("title" -> "thwip!")
        val request1 = FakeRequest("POST", testPath1).withBody(inputJson1)
        val result1 = libraryController.updateKeep(pubId1, keep1.externalId)(request1)
        status(result1) must equalTo(NO_CONTENT)

        db.readOnlyMaster { implicit s =>
          keepRepo.getByLibrary(lib1.id.get, 0, 5).map(_.title) === Seq(Some("thwip!"))
        }
      }
    }

    "update keep note in library" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, keep1, lib1) = db.readWrite { implicit s =>
          val user = UserFactory.user().withUsername("spiderman").saved
          val lib = library().withUser(user).saved
          val keep = KeepFactory.keep().withUser(user).withLibrary(lib).saved
          (user, keep, lib)
        }
        val libraryController = inject[LibraryController]
        val pubId1 = Library.publicId(lib1.id.get)(inject[PublicIdConfiguration])
        val testPath = com.keepit.controllers.website.routes.LibraryController.editKeepNote(pubId1, keep1.externalId).url
        inject[FakeUserActionsHelper].setUser(user1)

        // test adding a note (without hashtags)
        val result1 = libraryController.editKeepNote(pubId1, keep1.externalId)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "thwip!")))
        status(result1) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip!")
          collectionRepo.getHashtagsByKeepId(keep.id.get) === Set.empty
        }

        // test removing a note
        val result2 = libraryController.editKeepNote(pubId1, keep1.externalId)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "")))
        status(result2) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === None
          collectionRepo.getHashtagsByKeepId(keep.id.get) === Set.empty
        }

        // test adding a note (with hashtags)
        val result3 = libraryController.editKeepNote(pubId1, keep1.externalId)(FakeRequest("POST", testPath).withBody(Json.obj("note" -> "thwip! #spiders [#avengers] [#tonysucks] blah")))
        status(result3) must equalTo(NO_CONTENT)
        db.readOnlyMaster { implicit s =>
          val keep = keepRepo.getOpt(keep1.externalId).get
          keep.note === Some("thwip! #spiders [#avengers] [#tonysucks] blah")
          collectionRepo.getHashtagsByKeepId(keep.id.get).map(_.tag) === Set("tonysucks", "avengers")
        }

      }

    }

    "update library membership" in {
      withDb(modules: _*) { implicit injector =>
        val (user1, user2, user3, lib1) = db.readWrite { implicit s =>
          val user1 = user().withUsername("nickfury").saved
          val user2 = user().withUsername("quicksilver").saved
          val user3 = user().withUsername("scarletwitch").saved
          val lib1 = library().withUser(user1).saved // user1 owns lib1
          membership().withLibraryCollaborator(lib1, user2).saved // user2 is a collaborator in lib1 (has read_write access)

          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user1.id.get).get.access === LibraryAccess.OWNER
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib1.id.get, user2.id.get).get.access === LibraryAccess.READ_WRITE
          (user1, user2, user3, lib1)
        }

        def updateLibraryMembership(user: User, targetUser: User, lib: Library, access: String) = {
          val libraryController = inject[LibraryController]
          val pubLibId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])
          inject[FakeUserActionsHelper].setUser(user)
          val testPath = com.keepit.controllers.website.routes.LibraryController.updateLibraryMembership(pubLibId, targetUser.externalId).url
          val jsonBody = Json.obj("access" -> access)
          libraryController.updateLibraryMembership(pubLibId, targetUser.externalId)(FakeRequest("POST", testPath).withBody(jsonBody))
        }

        // test invalid library write access (error)
        status(updateLibraryMembership(user2, user2, lib1, "owner")) must equalTo(BAD_REQUEST)

        // test demote membership access
        status(updateLibraryMembership(user1, user2, lib1, "read_only")) must equalTo(NO_CONTENT)

        // test promote membership access
        status(updateLibraryMembership(user1, user2, lib1, "read_write")) must equalTo(NO_CONTENT)

        // test deactivate membership
        status(updateLibraryMembership(user1, user2, lib1, "none")) must equalTo(NO_CONTENT)

        // test membership not found (error)
        status(updateLibraryMembership(user1, user2, lib1, "read_only")) must equalTo(NOT_FOUND)
      }
    }

    "marketingSiteSuggestedLibraries" should {
      "return json array of library info" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit rw =>
            val user1 = user().withName("John", "Doe").saved
            val user2 = user().withName("Joe", "Blow").saved
            val user3 = user().withName("Jack", "Black").saved
            val lib1 = library().withName("Scala").withUser(user1).published().withMemberCount(3).saved
            val lib2 = library().withName("Java").withUser(user1).published().withMemberCount(2).saved

            // test that private libraries are not returned in the response
            val lib3 = library().withName("Private").withUser(user1).saved

            inject[KeepRepo].all() // force slick to create the table

            membership().withLibraryOwner(lib1).saved
            membership().withLibraryFollower(lib1, user2).saved
            membership().withLibraryFollower(lib1, user3).saved
            membership().withLibraryOwner(lib2).saved
            membership().withLibraryFollower(lib2, user3).saved

            inject[SystemValueRepo].save(SystemValue(
              name = MarketingSuggestedLibrarySystemValue.systemValueName,
              value = s"""
                   |[
                   |  { "id": 424242, "caption": "does not exist" },
                   |  { "id": ${lib2.id.get}, "color": "${LibraryColor.BLUE.hex}" },
                   |  { "id": ${lib1.id.get}, "caption": "yo dawg", "color": "${LibraryColor.RED.hex}" },
                   |  { "id": ${lib3.id.get} }
                   |]
                 """.stripMargin))
          }

          val call = com.keepit.controllers.website.routes.LibraryController.marketingSiteSuggestedLibraries()
          call.url === "/site/libraries/marketing-suggestions"
          call.method === "GET"

          val result = inject[LibraryController].marketingSiteSuggestedLibraries()(FakeRequest())
          status(result) === OK

          val libInfos = contentAsJson(result).as[Seq[LibraryCardInfo]]
          libInfos.size === 2
          libInfos(0).name === "Java"
          libInfos(0).numFollowers === 1
          libInfos(0).id.id must beMatching("^l.+") // tests public id
          libInfos(0).caption must beNone
          libInfos(1).name === "Scala"
          libInfos(1).numFollowers === 2
          libInfos(1).owner.fullName === "John Doe"
          libInfos(1).caption === Some("yo dawg")
        }
      }
    }
  }
}

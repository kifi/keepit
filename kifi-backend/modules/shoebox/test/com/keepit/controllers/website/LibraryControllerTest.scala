package com.keepit.controllers.website

import com.keepit.abook.TestABookServiceClientModule
import com.keepit.commanders.{ FullLibraryInfo, LibraryInfo }
import com.keepit.common.crypto.{ TestCryptoModule, PublicIdConfiguration, ShoeboxCryptoModule }
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.TestMailModule
import com.keepit.common.social.{ FakeAuthenticator, TestShoeboxAppSecureSocialModule, FakeSocialGraphModule }
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.time._
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, TestScrapeSchedulerConfigModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.{ FakeShoeboxServiceModule, FakeKeepImportsModule }
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.DateTime
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.SimpleResult
import play.api.test.Helpers._
import play.api.test._
import com.keepit.common.json.JsonFormatters._

import scala.concurrent.Future

class LibraryControllerTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    TestCryptoModule(),
    FakeAuthenticator(),
    ShoeboxFakeStoreModule(),
    TestABookServiceClientModule(),
    FakeKeepImportsModule(),
    TestMailModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeSearchServiceClientModule(),
    TestScrapeSchedulerConfigModule(),
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
          "isSearchableByOthers" -> false
        )
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1).withHeaders("userId" -> "1")
        val result1 = libraryController.addLibrary()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val parse1 = Json.parse(contentAsString(result1)).as[FullLibraryInfo]
        parse1.name === "Library1"
        parse1.slug.value === "lib1"
        parse1.visibility.value === "secret"
        parse1.keepCount === 0
        parse1.ownerId === user.externalId

        val inputJson2 = Json.obj(
          "name" -> "Invalid Library - Slug",
          "slug" -> "lib2 abcd",
          "visibility" -> "secret",
          "collaborators" -> Json.arr(),
          "followers" -> Json.arr(),
          "isSearchableByOthers" -> false
        )
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2).withHeaders("userId" -> "1")
        val result2: Future[SimpleResult] = libraryController.addLibrary()(request2)
        status(result2) must equalTo(BAD_REQUEST)
        contentType(result2) must beSome("application/json")

        // Re-add Library 1
        val request3 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(inputJson1).withHeaders("userId" -> "1")
        val result3: Future[SimpleResult] = libraryController.addLibrary()(request3)
        status(result3) must equalTo(BAD_REQUEST)
        contentType(result3) must beSome("application/json")

        val inputJson4 = Json.obj(
          "name" -> "Invalid Name - \"",
          "slug" -> "lib5",
          "visibility" -> "secret",
          "collaborators" -> Json.arr(),
          "followers" -> Json.arr(),
          "isSearchableByOthers" -> false
        )
        val request4 = FakeRequest("POST", com.keepit.controllers.website.routes.LibraryController.addLibrary().url).withBody(inputJson4).withHeaders("userId" -> "1")
        val result4: Future[SimpleResult] = libraryController.addLibrary()(request4)
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
          val user = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user, library)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.modifyLibrary(pubId).url

        val inputJson1 = Json.obj("name" -> "Library2")
        val request1 = FakeRequest("POST", testPath).withBody(inputJson1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.modifyLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        Json.parse(contentAsString(result1)).as[LibraryInfo].name === "Library2"

        val inputJson2 = Json.obj("slug" -> "lib2", "description" -> "asdf", "visibility" -> LibraryVisibility.ANYONE.value)
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2).withHeaders("userId" -> "1")
        val result2: Future[SimpleResult] = libraryController.modifyLibrary(pubId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val expected = Json.parse(
          s"""
             |{
             |"id":"${pubId.id}",
             |"name":"Library2",
             |"visibility":"anyone",
             |"shortDescription":"asdf",
             |"slug":"lib2",
             |"ownerId":"${user1.externalId}"
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
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, library1, library2)
        }
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.removeLibrary(pubId1).url
        val request1 = FakeRequest("POST", testPath1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.removeLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val testPath2 = com.keepit.controllers.website.routes.LibraryController.removeLibrary(pubId2).url
        val request2 = FakeRequest("POST", testPath2).withHeaders("userId" -> "1")
        val result2: Future[SimpleResult] = libraryController.removeLibrary(pubId2)(request2)
        status(result2) must equalTo(BAD_REQUEST)
        contentType(result2) must beSome("application/json")
      }
    }

    "get library by public id" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, lib1) = db.readWrite { implicit s =>
          val user = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          (user, library)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.getLibrary(pubId).url
        val request1 = FakeRequest("GET", testPath).withBody(Json.obj()).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.getLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected = Json.parse(
          s"""
             |{
             |"id":"${pubId.id}",
             |"name":"Library1",
             |"visibility":"secret",
             |"slug":"lib1",
             |"ownerId":"${user1.externalId}",
             |"collaborators":{"count":0,"users":[],"isMore":false},
             |"followers":{"count":0,"users":[],"isMore":false},
             |"keepCount":0
             |}
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)
      }
    }

    "get library by user id" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val libraryController = inject[LibraryController]

        val (user1, user2, lib1, lib2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Aaron", lastName = "A", createdAt = t1))
          val user2 = userRepo.save(User(firstName = "Baron", lastName = "B", createdAt = t1))
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), isSearchableByOthers = false, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, user2, library1, library2)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.getLibrariesByUser.url
        val request1 = FakeRequest("GET", testPath).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.getLibrariesByUser()(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected = Json.parse(
          s"""
            |{"libraries":
              |[
                |{"info":{
                |"id":"${pubId.id}",
                |"name":"Library1",
                |"visibility":"secret",
                |"slug":"lib1",
                |"ownerId":"${user1.externalId}"},
                |"access":"owner"}
              |]
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
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.SECRET, isSearchableByOthers = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (user1, user2, user3, library)
        }

        val pubId = Library.publicId(lib1.id.get)
        val testPath = com.keepit.controllers.website.routes.LibraryController.inviteUsersToLibrary(pubId).url

        val inputJson1 =
          Json.obj("pairs" -> Some(Json.toJson(Seq(
            (user2.externalId, LibraryAccess.READ_ONLY),
            (user3.externalId, LibraryAccess.READ_ONLY)))
          ))

        val request1 = FakeRequest("POST", testPath).withBody(inputJson1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.inviteUsersToLibrary(pubId)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
            |[
            | {"user":"${user2.externalId}","access":"${LibraryAccess.READ_ONLY.value}"},
            | {"user":"${user3.externalId}","access":"${LibraryAccess.READ_ONLY.value}"}
            |]
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)
      }
    }
  }
}

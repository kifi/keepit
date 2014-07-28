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
          "keepDiscoveryEnabled" -> false
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
          "keepDiscoveryEnabled" -> false
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
          "keepDiscoveryEnabled" -> false
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
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
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

        val inputJson2 = Json.obj("slug" -> "lib2", "description" -> "asdf", "visibility" -> LibraryVisibility.PUBLISHED.value)
        val request2 = FakeRequest("POST", testPath).withBody(inputJson2).withHeaders("userId" -> "1")
        val result2: Future[SimpleResult] = libraryController.modifyLibrary(pubId)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")

        val expected = Json.parse(
          s"""
             |{
             |"id":"${pubId.id}",
             |"name":"Library2",
             |"visibility":"published",
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
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.SECRET))
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
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          (user, library)
        }

        val pubId1 = Library.publicId(lib1.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getLibrary(pubId1).url
        val request1 = FakeRequest("GET", testPath1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.getLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected = Json.parse(
          s"""
             |{
             |"id":"${pubId1.id}",
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
          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), memberCount = 1, visibility = LibraryVisibility.SECRET))
          val library2 = libraryRepo.save(Library(name = "Library2", ownerId = user2.id.get, slug = LibrarySlug("lib2"), memberCount = 1, visibility = LibraryVisibility.SECRET))
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
          val library = libraryRepo.save(Library(name = "Library1", ownerId = user1.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.SECRET, memberCount = 1))
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

    "join or decline library invites" in {
      withDb(modules: _*) { implicit injector =>
        implicit val config = inject[PublicIdConfiguration]
        val libraryController = inject[LibraryController]
        val t1 = new DateTime(2014, 7, 21, 6, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val (user1, user2, lib1, lib2, inv1, inv2) = db.readWrite { implicit s =>
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))
          val userB = userRepo.save(User(firstName = "Bulba", lastName = "Saur", createdAt = t1))

          // user B owns 2 libraries
          val libraryB1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val libraryB2 = libraryRepo.save(Library(name = "Library2", ownerId = userB.id.get, slug = LibrarySlug("lib2"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = libraryB2.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          // user B invites A to both libraries
          val inv1 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB1.id.get, ownerId = userB.id.get, userId = userA.id.get, access = LibraryAccess.READ_INSERT))
          val inv2 = libraryInviteRepo.save(LibraryInvite(libraryId = libraryB2.id.get, ownerId = userB.id.get, userId = userA.id.get, access = LibraryAccess.READ_INSERT))
          (userA, userB, libraryB1, libraryB2, inv1, inv2)
        }

        val pubId1 = LibraryInvite.publicId(inv1.id.get)
        val pubId2 = LibraryInvite.publicId(inv2.id.get)

        val testPathJoin = com.keepit.controllers.website.routes.LibraryController.joinLibrary(pubId1).url
        val testPathDecline = com.keepit.controllers.website.routes.LibraryController.declineLibrary(pubId2).url

        val request1 = FakeRequest("POST", testPathJoin).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.joinLibrary(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected = Json.parse(
          s"""
             |{
             |"id":"${Library.publicId(lib1.id.get).id}",
             |"name":"Library1",
             |"visibility":"discoverable",
             |"slug":"lib1",
             |"ownerId":"${user2.externalId}"
             |}
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected)

        val request2 = FakeRequest("POST", testPathDecline).withHeaders("userId" -> "1")
        val result2: Future[SimpleResult] = libraryController.declineLibrary(pubId2)(request2)
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

          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userB.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userB.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          (userA, userB, library1)
        }

        val pubId1 = Library.publicId(lib1.id.get)

        val testPath1 = com.keepit.controllers.website.routes.LibraryController.leaveLibrary(pubId1).url
        val request1 = FakeRequest("POST", testPath1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.leaveLibrary(pubId1)(request1)
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
          val userA = userRepo.save(User(firstName = "Aaron", lastName = "Hsu", createdAt = t1))

          val library1 = libraryRepo.save(Library(name = "Library1", ownerId = userA.id.get, slug = LibrarySlug("lib1"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = library1.id.get, userId = userA.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val uri1 = uriRepo.save(NormalizedURI.withHash(site1, Some("Google")))
          val uri2 = uriRepo.save(NormalizedURI.withHash(site2, Some("Amazon")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("k1"), userId = userA.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), libraryId = Some(library1.id.get)))
          val keep2 = keepRepo.save(Keep(title = Some("k2"), userId = userA.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3), libraryId = Some(library1.id.get)))

          (userA, library1, keep1, keep2)
        }

        val pubId1 = Library.publicId(lib1.id.get)
        val testPath1 = com.keepit.controllers.website.routes.LibraryController.getKeeps(pubId1).url
        val request1 = FakeRequest("POST", testPath1).withHeaders("userId" -> "1")
        val result1: Future[SimpleResult] = libraryController.getKeeps(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")

        val expected1 = Json.parse(
          s"""
             |[{
             |"id":"${keep1.externalId}",
             |"title":"k1",
             |"url":"http://www.google.com/",
             |"isPrivate":false
             |},
             |{
             |"id":"${keep2.externalId}",
             |"title":"k2",
             |"url":"http://www.amazon.com/",
             |"isPrivate":false
             |}]
           """.stripMargin)
        Json.parse(contentAsString(result1)) must equalTo(expected1)
      }
    }

  }
}

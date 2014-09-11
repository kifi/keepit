package com.keepit.controllers.ext

import com.keepit.common.controller.{ FakeActionAuthenticatorModule, FakeActionAuthenticator }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicIdConfiguration }
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

class ExtLibraryControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeScraperServiceClientModule(),
    FakeActionAuthenticatorModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeHttpClientModule()
  )

  "ExtLibraryController" should {

    "get libraries" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Morgan", lastName = "Freeman", username = Some(Username("morgan"))))
          val lib1 = libraryRepo.save(Library(name = "Million Dollar Baby", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("baby"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val user2 = userRepo.save(User(firstName = "Michael", lastName = "Caine", username = Some(Username("michael"))))
          // Give READ_INSERT access to Freeman
          val lib2 = libraryRepo.save(Library(name = "Dark Knight", ownerId = user2.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("darkknight"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.READ_INSERT, showInSearch = true))

          // Give READ_ONLY access to Freeman
          val lib3 = libraryRepo.save(Library(name = "Now You See Me", ownerId = user2.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("magic"), memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))

          (user1, user2, lib1, lib2, lib3)
        }
        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get).id
        val pubId2 = Library.publicId(lib2.id.get).id
        val path = com.keepit.controllers.ext.routes.ExtLibraryController.getLibraries().url
        path === "/ext/libraries"

        inject[FakeActionAuthenticator].setUser(user1)
        val request = FakeRequest("GET", path)
        val result = inject[ExtLibraryController].getLibraries()(request)
        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")

        Json.parse(contentAsString(result)) must equalTo(Json.parse(
          s"""
            |{"libraries" :
               |[
                 |{
                  | "id": "$pubId1",
                  | "name": "Million Dollar Baby",
                  | "path": "/morgan/baby",
                  | "visibility": "discoverable"
                 |},
                 |{
                  | "id": "$pubId2",
                  | "name": "Dark Knight",
                  | "path": "/michael/darkknight",
                  | "visibility": "discoverable"
                 |}
               |]
             |}""".stripMargin
        ))
      }
    }

    "add keep to library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, lib3) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "Kanye", lastName = "West", username = Some(Username("kanye"))))
          val lib1 = libraryRepo.save(Library(name = "Genius", ownerId = user1.id.get, slug = LibrarySlug("genius"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          val user2 = userRepo.save(User(firstName = "Taylor", lastName = "Swift", username = Some(Username("taylor"))))
          val lib2 = libraryRepo.save(Library(name = "My VMA Award", ownerId = user2.id.get, slug = LibrarySlug("myvma"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val lib3 = libraryRepo.save(Library(name = "New Album", ownerId = user2.id.get, slug = LibrarySlug("newalbum"), visibility = LibraryVisibility.SECRET, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user2.id.get, access = LibraryAccess.OWNER, showInSearch = true))

          // Kayne West has membership to these libraries... for some reason
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.READ_INSERT, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          (user1, user2, lib1, lib2, lib3)
        }

        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val pubId3 = Library.publicId(lib3.id.get)
        val path1 = com.keepit.controllers.ext.routes.ExtLibraryController.addKeep(pubId1).url
        val path2 = com.keepit.controllers.ext.routes.ExtLibraryController.addKeep(pubId2).url
        val path3 = com.keepit.controllers.ext.routes.ExtLibraryController.addKeep(pubId3).url
        path1 === s"/ext/libraries/${pubId1.id}/keeps"

        inject[FakeActionAuthenticator].setUser(user1)
        val extLibraryController = inject[ExtLibraryController]

        val request1 = FakeRequest("POST", path1).withBody(
          Json.obj(
            "title" -> "kayne-fidence",
            "url" -> "http://www.imagenius.com",
            "guided" -> false))
        val result1 = extLibraryController.addKeep(pubId1)(request1)
        status(result1) must equalTo(OK)
        contentType(result1) must beSome("application/json")
        val keep1 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 10, 0).head }
        Json.parse(contentAsString(result1)) must equalTo(Json.parse(
          s"""
            |{
              |"id":"${keep1.externalId}",
              |"title":"kayne-fidence",
              |"url":"http://www.imagenius.com",
              |"isPrivate":false,
              |"libraryId":"${pubId1.id}"}""".stripMargin
        ))

        val request2 = FakeRequest("POST", path2).withBody(
          Json.obj(
            "title" -> "IMMA LET YOU FINISH",
            "url" -> "http://www.beyonceisbetter.com",
            "guided" -> false))
        val result2 = extLibraryController.addKeep(pubId2)(request2)
        status(result2) must equalTo(OK)
        contentType(result2) must beSome("application/json")
        val keep2 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib2.id.get, 10, 0).head }
        Json.parse(contentAsString(result2)) must equalTo(Json.parse(
          s"""
            |{
              |"id":"${keep2.externalId}",
              |"title":"IMMA LET YOU FINISH",
              |"url":"http://www.beyonceisbetter.com",
              |"isPrivate":true,
              |"libraryId":"${pubId2.id}"}""".stripMargin
        ))

        val request3 = FakeRequest("POST", path3).withBody(
          Json.obj(
            "title" -> "IMMA LET YOU FINISH",
            "url" -> "http://www.beyonceisbetter.com",
            "guided" -> false))
        val result3 = extLibraryController.addKeep(pubId3)(request3)
        status(result3) must equalTo(BAD_REQUEST)
      }
    }
  }
}

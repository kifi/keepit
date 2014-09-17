package com.keepit.controllers.ext

import com.google.inject.Injector

import com.keepit.common.controller.{ FakeActionAuthenticatorModule, FakeActionAuthenticator }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.time._
import com.keepit.model.{ FakeSliderHistoryTrackerModule, Keep, KeepSource }
import com.keepit.model.{ Library, LibraryAccess, LibraryMembership, LibrarySlug, LibraryVisibility }
import com.keepit.model.{ NormalizedURI, URLFactory, User, Username }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

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

        val result = getLibraries(user1)
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

        val result1 = addKeep(user1, pubId1, Json.obj(
          "title" -> "kayne-fidence",
          "url" -> "http://www.imagenius.com",
          "guided" -> false))
        status(result1) === OK
        contentType(result1) must beSome("application/json")
        val keep1 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 10, 0).head }
        contentAsString(result1) === s"""{"id":"${keep1.externalId}","mine":true,"removable":true,"libraryId":"${pubId1.id}"}"""

        val result2 = addKeep(user1, pubId2, Json.obj(
          "title" -> "IMMA LET YOU FINISH",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result2) === OK
        contentType(result2) must beSome("application/json")
        val keep2 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib2.id.get, 10, 0).head }
        contentAsString(result2) === s"""{"id":"${keep2.externalId}","mine":true,"removable":true,"secret":true,"libraryId":"${pubId2.id}"}"""

        val result3 = addKeep(user1, pubId3, Json.obj(
          "title" -> "IMMA LET YOU FINISH",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result3) === FORBIDDEN
      }
    }

    "remove keep from library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val t1 = new DateTime(2014, 8, 1, 4, 0, 0, 0, DEFAULT_DATE_TIME_ZONE)

        val (user1, user2, lib1, lib2, keep1, keep2, keep3) = db.readWrite { implicit s =>

          val user1 = userRepo.save(User(firstName = "Colin", lastName = "Kaepernick", username = Some(Username("qb")), createdAt = t1))
          val lib1 = libraryRepo.save(Library(name = "49ers UberL33t Football Plays", ownerId = user1.id.get, slug = LibrarySlug("football"), visibility = LibraryVisibility.DISCOVERABLE, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, createdAt = t1))
          val lib2 = libraryRepo.save(Library(name = "shoes", ownerId = user1.id.get, slug = LibrarySlug("shoes"), visibility = LibraryVisibility.PUBLISHED, memberCount = 1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true, createdAt = t1))

          // coach has RW access to kaep's football library
          val user2 = userRepo.save(User(firstName = "Jim", lastName = "Harbaugh", username = Some(Username("coach")), createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true, createdAt = t1))

          val uri1 = uriRepo.save(NormalizedURI.withHash("www.runfast.com", Some("Run")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("www.throwlong.com", Some("Throw")))
          val uri3 = uriRepo.save(NormalizedURI.withHash("www.howtonotchoke.com", Some("DontChoke")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("Run"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), createdAt = t1.plusMinutes(1)))
          val keep2 = keepRepo.save(Keep(title = Some("Throw"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), createdAt = t1.plusMinutes(2)))
          val keep3 = keepRepo.save(Keep(title = Some("DontChoke"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), createdAt = t1.plusMinutes(3)))
          (user1, user2, lib1, lib2, keep1, keep2, keep3)
        }
        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)

        db.readOnlyMaster { implicit s => keepRepo.count } === 3

        // test unkeep from own library
        status(removeKeep(user1, pubId1, keep1.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 10, 0).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test incorrect unkeep from own library (keep exists but in wrong library)
        status(removeKeep(user1, pubId2, keep2.externalId)) === BAD_REQUEST
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 10, 0).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test unkeep from someone else's library (have RW access)
        status(removeKeep(user2, pubId1, keep3.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 10, 0).map(_.title.get) === Seq("Throw") }
      }
    }
  }

  private def getLibraries(user: User)(implicit injector: Injector): Future[Result] = {
    val route = com.keepit.controllers.ext.routes.ExtLibraryController.getLibraries()
    inject[FakeActionAuthenticator].setUser(user)
    inject[ExtLibraryController].getLibraries()(FakeRequest(route.method, route.url))
  }

  private def addKeep(user: User, libraryId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    val route = com.keepit.controllers.ext.routes.ExtLibraryController.addKeep(libraryId)
    inject[FakeActionAuthenticator].setUser(user)
    inject[ExtLibraryController].addKeep(libraryId)(FakeRequest(route.method, route.url).withBody(body))
  }

  private def removeKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    val route = com.keepit.controllers.ext.routes.ExtLibraryController.removeKeep(libraryId, keepId)
    inject[FakeActionAuthenticator].setUser(user)
    inject[ExtLibraryController].removeKeep(libraryId, keepId)(FakeRequest(route.method, route.url))
  }
}

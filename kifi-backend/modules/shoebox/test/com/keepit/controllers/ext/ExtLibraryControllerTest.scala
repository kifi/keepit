package com.keepit.controllers.ext

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule

import com.keepit.common.controller.{ FakeUserActionsHelper }
import com.keepit.common.crypto.{ FakeCryptoModule, PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.model.{ Collection, FakeSliderHistoryTrackerModule, Hashtag, Keep, KeepSource, KeepToCollection }
import com.keepit.model.{ Library, LibraryAccess, LibraryMembership, LibraryMembershipStates, LibrarySlug, LibraryStates, LibraryVisibility }
import com.keepit.model.{ NormalizedURI, URLFactory, UrlHash, User, Username }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.shoebox.{ FakeKeepImportsModule, FakeShoeboxServiceModule }
import com.keepit.social.BasicUser
import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }

import org.joda.time.DateTime
import org.specs2.mutable.Specification

import play.api.libs.json.{ Json, JsObject }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class ExtLibraryControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  val controllerTestModules = Seq(
    FakeCryptoModule(),
    FakeShoeboxServiceModule(),
    FakeScrapeSchedulerModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
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

    "create library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val user1 = db.readWrite { implicit s =>
          userRepo.save(User(firstName = "U", lastName = "1"))
        }
        implicit val config = inject[PublicIdConfiguration]

        // add new library
        status(createLibrary(user1, Json.obj("name" -> "Lib 1", "visibility" -> "secret"))) === OK
        db.readOnlyMaster { implicit s =>
          val lib = libraryRepo.getBySlugAndUserId(user1.id.get, LibrarySlug("lib1"))
          lib.get.name === "Lib 1"
          libraryMembershipRepo.getWithLibraryIdAndUserId(lib.get.id.get, user1.id.get).get.access === LibraryAccess.OWNER
        }

        // duplicate name
        status(createLibrary(user1, Json.obj("name" -> "Lib 1", "visibility" -> "secret"))) === BAD_REQUEST

        // invalid name
        status(createLibrary(user1, Json.obj("name" -> "Lib/\" 3", "visibility" -> "secret"))) === BAD_REQUEST

        db.readOnlyMaster { implicit s =>
          libraryRepo.count === 1
        }
      }
    }

    "get library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib1, lib2, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib1 = libraryRepo.save(Library(name = "L1", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("l1"), memberCount = 1))
          val lib2 = libraryRepo.save(Library(name = "L2", ownerId = user1.id.get, visibility = LibraryVisibility.PUBLISHED, slug = LibrarySlug("l2"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true, state = LibraryMembershipStates.INACTIVE))
          (user1, user2, lib1, lib2, mem1, mem2)
        }
        implicit val config = inject[PublicIdConfiguration]
        val lib1PubId = Library.publicId(lib1.id.get)
        val lib2PubId = Library.publicId(lib2.id.get)

        Json.parse(contentAsString(getLibrary(user1, lib1PubId))) === Json.obj(
          "name" -> "L1",
          "slug" -> "l1",
          "visibility" -> "secret",
          "owner" -> BasicUser.fromUser(user1),
          "keeps" -> 0,
          "followers" -> 0)

        status(getLibrary(user2, lib2PubId)) === OK

        status(getLibrary(user2, lib1PubId)) === FORBIDDEN

        db.readWrite { implicit s =>
          libraryMembershipRepo.save(mem2.withState(LibraryMembershipStates.ACTIVE))
        }

        status(getLibrary(user2, lib1PubId)) === OK
      }
    }

    "delete library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))
          libraryRepo.all.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.ACTIVE))
          (user1, user2, lib, mem1, mem2)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        status(deleteLibrary(user2, libPubId)) === FORBIDDEN

        db.readOnlyMaster { implicit s =>
          libraryRepo.all.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.ACTIVE))
        }

        status(deleteLibrary(user1, libPubId)) === NO_CONTENT

        db.readOnlyMaster { implicit s =>
          libraryRepo.all.map { l => (l.id, l.state) } === Seq((lib.id, LibraryStates.INACTIVE))
        }
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
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib2.id.get, userId = user1.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib3.id.get, userId = user1.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          (user1, user2, lib1, lib2, lib3)
        }

        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)
        val pubId3 = Library.publicId(lib3.id.get)

        // keep to own library
        val result1 = addKeep(user1, pubId1, Json.obj(
          "title" -> "kayne-fidence",
          "url" -> "http://www.imagenius.com",
          "guided" -> false))
        status(result1) === OK
        contentType(result1) must beSome("application/json")
        val keep1 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 0, 10).head }
        contentAsString(result1) === s"""{"id":"${keep1.externalId}","mine":true,"removable":true,"libraryId":"${pubId1.id}","title":"kayne-fidence"}"""

        // keep to someone else's library
        val result2 = addKeep(user1, pubId2, Json.obj(
          "title" -> "T 2",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result2) === OK
        contentType(result2) must beSome("application/json")
        val keep2 = db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib2.id.get, 0, 10).head }
        contentAsString(result2) === s"""{"id":"${keep2.externalId}","mine":true,"removable":true,"secret":true,"libraryId":"${pubId2.id}","title":"T 2"}"""

        // keep to someone else's library again (should be idempotent)
        val result3 = addKeep(user1, pubId2, Json.obj(
          "title" -> "T 3",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result3) === OK
        contentAsString(result3) === s"""{"id":"${keep2.externalId}","mine":true,"removable":true,"secret":true,"libraryId":"${pubId2.id}","title":"T 3"}"""

        // try to keep to someone else's library without sufficient access
        val result4 = addKeep(user1, pubId3, Json.obj(
          "title" -> "IMMA LET YOU FINISH",
          "url" -> "http://www.beyonceisbetter.com",
          "guided" -> false))
        status(result4) === FORBIDDEN
      }
    }

    "get keep in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          val keep = keepInLibrary(user1, lib, "http://foo.com", "Foo", Seq("Bar", "Baz"))
          (user1, user2, lib, mem1, mem2, keep)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can get own keep in own library
        val result1 = getKeep(user1, libPubId, keep.externalId)
        status(result1) === OK
        contentType(result1) must beSome("application/json")
        contentAsString(result1) === """{"title":"Foo","tags":["Bar","Baz"]}"""

        // invalid keep ID
        val result2 = getKeep(user1, libPubId, ExternalId())
        status(result2) === NOT_FOUND
        contentType(result2) must beSome("application/json")
        contentAsString(result2) === """{"error":"keep_not_found"}"""

        // other user with library access can get keep
        val result3 = getKeep(user2, libPubId, keep.externalId)
        status(result3) === OK
        contentType(result3) must beSome("application/json")
        contentAsString(result3) === """{"title":"Foo","tags":["Bar","Baz"]}"""

        // other user with library access revoked cannot get keep
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.withState(LibraryMembershipStates.INACTIVE)) }
        val result4 = getKeep(user2, libPubId, keep.externalId)
        status(result4) === FORBIDDEN
        contentType(result4) must beSome("application/json")
        contentAsString(result4) === """{"error":"library_access_denied"}"""
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

          // coach has RW access to keep's football library
          val user2 = userRepo.save(User(firstName = "Jim", lastName = "Harbaugh", username = Some(Username("coach")), createdAt = t1))
          libraryMembershipRepo.save(LibraryMembership(libraryId = lib1.id.get, userId = user2.id.get, access = LibraryAccess.READ_WRITE, showInSearch = true, createdAt = t1))

          val uri1 = uriRepo.save(NormalizedURI.withHash("www.runfast.com", Some("Run")))
          val uri2 = uriRepo.save(NormalizedURI.withHash("www.throwlong.com", Some("Throw")))
          val uri3 = uriRepo.save(NormalizedURI.withHash("www.howtonotchoke.com", Some("DontChoke")))

          val url1 = urlRepo.save(URLFactory(url = uri1.url, normalizedUriId = uri1.id.get))
          val url2 = urlRepo.save(URLFactory(url = uri2.url, normalizedUriId = uri2.id.get))
          val url3 = urlRepo.save(URLFactory(url = uri3.url, normalizedUriId = uri3.id.get))

          val keep1 = keepRepo.save(Keep(title = Some("Run"), userId = user1.id.get, url = url1.url, urlId = url1.id.get,
            uriId = uri1.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint, createdAt = t1.plusMinutes(1)))
          val keep2 = keepRepo.save(Keep(title = Some("Throw"), userId = user1.id.get, url = url2.url, urlId = url2.id.get,
            uriId = uri2.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint, createdAt = t1.plusMinutes(2)))
          val keep3 = keepRepo.save(Keep(title = Some("DontChoke"), userId = user1.id.get, url = url3.url, urlId = url3.id.get,
            uriId = uri3.id.get, source = KeepSource.keeper, visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint, createdAt = t1.plusMinutes(3)))
          (user1, user2, lib1, lib2, keep1, keep2, keep3)
        }
        implicit val config = inject[PublicIdConfiguration]
        val pubId1 = Library.publicId(lib1.id.get)
        val pubId2 = Library.publicId(lib2.id.get)

        db.readOnlyMaster { implicit s => keepRepo.count } === 3

        // test unkeep from own library
        status(removeKeep(user1, pubId1, keep1.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test unkeep from own library again (should be idempotent)
        status(removeKeep(user1, pubId1, keep1.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test incorrect unkeep from own library (keep exists but in wrong library)
        status(removeKeep(user1, pubId2, keep2.externalId)) === BAD_REQUEST
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("DontChoke", "Throw") }

        // test unkeep from someone else's library (have RW access)
        status(removeKeep(user2, pubId1, keep3.externalId)) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.getByLibrary(lib1.id.get, 0, 10).map(_.title.get) === Seq("Throw") }
      }
    }

    "update keep in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          val keep = keepInLibrary(user1, lib, "http://foo.com", "Foo")
          (user1, user2, lib, mem1, mem2, keep)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can update own keep in own library
        status(updateKeep(user1, libPubId, keep.externalId, Json.obj("title" -> "Bar"))) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // invalid keep ID
        status(updateKeep(user1, libPubId, ExternalId(), Json.obj("title" -> "Cat"))) === NOT_FOUND
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // other user with read-only library access cannot update keep
        status(updateKeep(user2, libPubId, keep.externalId, Json.obj("title" -> "pwned"))) === FORBIDDEN
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Bar" }

        // other user with write access can update keep
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.copy(access = LibraryAccess.READ_WRITE)) }
        status(updateKeep(user2, libPubId, keep.externalId, Json.obj("title" -> "Dat"))) === NO_CONTENT
        db.readOnlyMaster { implicit s => keepRepo.get(keep.id.get).title.get === "Dat" }
      }
    }

    "tag and untag keep in library" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep1, keep2) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          val keep1 = keepInLibrary(user1, lib, "http://foo.com", "Foo")
          val keep2 = keepInLibrary(user1, lib, "http://bar.com", "Bar", Seq("aa aa", "b b"))
          (user1, user2, lib, mem1, mem2, keep1, keep2)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])

        // user can tag own keeps in own library
        status(tagKeep(user1, libPubId, keep1.externalId, "c")) === OK
        status(tagKeep(user1, libPubId, keep1.externalId, "c")) === OK // idempotent
        status(tagKeep(user1, libPubId, keep1.externalId, "dd")) === OK
        status(tagKeep(user1, libPubId, keep2.externalId, "e e e")) === OK
        db.readOnlyMaster { implicit s =>
          collectionRepo.getTagsByKeepId(keep1.id.get) === Set(Hashtag("c"), Hashtag("dd"))
          collectionRepo.getTagsByKeepId(keep2.id.get) === Set(Hashtag("aa aa"), Hashtag("b b"), Hashtag("e e e"))
        }

        // user can untag own keeps in own library
        status(untagKeep(user1, libPubId, keep1.externalId, "dd")) === NO_CONTENT
        status(untagKeep(user1, libPubId, keep1.externalId, "dd")) === NO_CONTENT // idempotent
        status(untagKeep(user1, libPubId, keep1.externalId, "xyz")) === NO_CONTENT // succeeds when a no-op
        status(untagKeep(user1, libPubId, keep2.externalId, "b b")) === NO_CONTENT
        db.readOnlyMaster { implicit s =>
          collectionRepo.getTagsByKeepId(keep1.id.get) === Set(Hashtag("c"))
          collectionRepo.getTagsByKeepId(keep2.id.get) === Set(Hashtag("aa aa"), Hashtag("e e e"))
        }

        // invalid keep ID
        status(tagKeep(user1, libPubId, ExternalId(), "zzz")) === NOT_FOUND

        // other user with read-only library access cannot tag or untag keep
        status(tagKeep(user2, libPubId, keep1.externalId, "pwned")) === FORBIDDEN
        status(untagKeep(user2, libPubId, keep1.externalId, "c")) === FORBIDDEN
        db.readOnlyMaster { implicit s => collectionRepo.getTagsByKeepId(keep1.id.get) === Set(Hashtag("c")) }

        // other user with write access cannot yet tag or untag keep. TODO: fix when tags are in libraries
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.copy(access = LibraryAccess.READ_WRITE)) }
        status(tagKeep(user2, libPubId, keep1.externalId, "collab")) === FORBIDDEN
        status(untagKeep(user2, libPubId, keep1.externalId, "c")) === FORBIDDEN
        db.readOnlyMaster { implicit s => collectionRepo.getTagsByKeepId(keep1.id.get) === Set(Hashtag("c")) }
      }
    }

    "search tags" in {
      withDb(controllerTestModules: _*) { implicit injector =>
        val (user1, user2, lib, mem1, mem2, keep) = db.readWrite { implicit s =>
          val user1 = userRepo.save(User(firstName = "U", lastName = "1"))
          val user2 = userRepo.save(User(firstName = "U", lastName = "2"))
          val lib = libraryRepo.save(Library(name = "L", ownerId = user1.id.get, visibility = LibraryVisibility.DISCOVERABLE, slug = LibrarySlug("l"), memberCount = 1))
          val mem1 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user1.id.get, access = LibraryAccess.OWNER, showInSearch = true))
          val mem2 = libraryMembershipRepo.save(LibraryMembership(libraryId = lib.id.get, userId = user2.id.get, access = LibraryAccess.READ_ONLY, showInSearch = true))
          val keep = keepInLibrary(user1, lib, "http://foo.com", "Foo")
          (user1, user2, lib, mem1, mem2, keep)
        }
        val libPubId = Library.publicId(lib.id.get)(inject[PublicIdConfiguration])
        status(tagKeep(user1, libPubId, keep.externalId, "animal")) === OK
        status(tagKeep(user1, libPubId, keep.externalId, "aardvark")) === OK
        status(tagKeep(user1, libPubId, keep.externalId, "Awesome")) === OK

        // user can search tags in own library
        contentAsString(searchTags(user1, libPubId, keep.externalId, "a", 2)) === """[{"tag":"aardvark","matches":[[0,1]]},{"tag":"animal","matches":[[0,1]]}]"""
        contentAsString(searchTags(user1, libPubId, keep.externalId, "s", 2)) === """[]"""

        /* todo(Léo): reconsider when tags have been figured out from a product perspective
        // other user with read access to library can search tags
        contentAsString(searchTags(user2, libPubId, "a", 3)) === """[{"tag":"aardvark","matches":[[0,1]]},{"tag":"animal","matches":[[0,1]]},{"tag":"Awesome","matches":[[0,1]]}]"""
        contentAsString(searchTags(user2, libPubId, "s", 3)) === """[]"""
        */

        // other user without read access to library cannot search tags
        db.readWrite { implicit s => libraryMembershipRepo.save(mem2.copy(state = LibraryMembershipStates.INACTIVE)) }
        status(searchTags(user2, libPubId, keep.externalId, "a", 3)) === FORBIDDEN
      }
    }
  }

  private def getLibraries(user: User)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibraries()(request(routes.ExtLibraryController.getLibraries()))
  }

  private def createLibrary(user: User, body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.createLibrary()(request(routes.ExtLibraryController.createLibrary()).withBody(body))
  }

  private def getLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getLibrary(libraryId)(request(routes.ExtLibraryController.getLibrary(libraryId)))
  }

  private def deleteLibrary(user: User, libraryId: PublicId[Library])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.deleteLibrary(libraryId)(request(routes.ExtLibraryController.deleteLibrary(libraryId)))
  }

  private def addKeep(user: User, libraryId: PublicId[Library], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.addKeep(libraryId)(request(routes.ExtLibraryController.addKeep(libraryId)).withBody(body))
  }

  private def getKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.getKeep(libraryId, keepId, None)(request(routes.ExtLibraryController.getKeep(libraryId, keepId)))
  }

  private def removeKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep])(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.removeKeep(libraryId, keepId)(request(routes.ExtLibraryController.removeKeep(libraryId, keepId)))
  }

  private def updateKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], body: JsObject)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.updateKeep(libraryId, keepId)(request(routes.ExtLibraryController.updateKeep(libraryId, keepId)).withBody(body))
  }

  private def tagKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], tag: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.tagKeep(libraryId, keepId, tag)(request(routes.ExtLibraryController.tagKeep(libraryId, keepId, tag)))
  }

  private def untagKeep(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], tag: String)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.untagKeep(libraryId, keepId, tag)(request(routes.ExtLibraryController.untagKeep(libraryId, keepId, tag)))
  }

  private def searchTags(user: User, libraryId: PublicId[Library], keepId: ExternalId[Keep], q: String, n: Int)(implicit injector: Injector): Future[Result] = {
    inject[FakeUserActionsHelper].setUser(user)
    controller.suggestTags(libraryId, keepId, q, Some(n))(request(routes.ExtLibraryController.suggestTags(libraryId, keepId, q, Some(n))))
  }

  private def keepInLibrary(user: User, lib: Library, url: String, title: String, tags: Seq[String] = Seq.empty)(implicit injector: Injector, session: RWSession): Keep = {
    val uri = uriRepo.save(NormalizedURI(url = url, urlHash = UrlHash(url.hashCode.toString)))
    val urlId = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get)).id.get
    val keep = keepRepo.save(Keep(
      title = Some(title), userId = user.id.get, uriId = uri.id.get, urlId = urlId, url = uri.url,
      source = KeepSource.keeper, visibility = lib.visibility, libraryId = lib.id, inDisjointLib = lib.isDisjoint))
    tags.foreach { tag =>
      val coll = collectionRepo.save(Collection(userId = keep.userId, name = Hashtag(tag)))
      keepToCollectionRepo.save(KeepToCollection(keepId = keep.id.get, collectionId = coll.id.get))
    }
    keep
  }

  private def controller(implicit injector: Injector) = inject[ExtLibraryController]
  private def request(route: Call) = FakeRequest(route.method, route.url)
}

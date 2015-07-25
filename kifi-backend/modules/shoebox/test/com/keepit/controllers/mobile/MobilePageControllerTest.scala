package com.keepit.controllers.mobile

import akka.actor.ActorSystem
import akka.testkit.TestKit
import com.keepit.abook.FakeABookServiceClientModule

import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import org.specs2.mutable.{ SpecificationLike }

import org.joda.time.DateTime
import com.keepit.common.time._

import com.keepit.search._
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.test.{ ShoeboxTestInjector, DbInjectionHelper }

import play.api.libs.json.{ Json }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.FakeAnalyticsModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.common.actor.FakeActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule
import com.keepit.model.UserFactoryHelper._

class MobilePageControllerTest extends TestKit(ActorSystem()) with SpecificationLike with ShoeboxTestInjector with DbInjectionHelper {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    FakeAnalyticsModule(),
    FakeShoeboxStoreModule(),
    FakeActorSystemModule(),
    FakeAirbrakeModule(),
    FakeUserActionsModule(),
    FakeSearchServiceClientModule(),
    FakeSliderHistoryTrackerModule(),
    FakeABookServiceClientModule(),
    FakeCortexServiceClientModule(),
    FakeSocialGraphModule(),
    FakeCuratorServiceClientModule()
  )

  "mobileController" should {
    "return connected users from the database" in {
      withDb(mobileControllerTestModules: _*) { implicit injector =>
        val path = com.keepit.controllers.mobile.routes.MobilePageController.getPageDetails().toString
        path === "/m/1/page/details"

        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val googleUrl = "http://www.google.com"
        val (user1, uri) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("Shanee", "Smith").withId("aaaaaaaa-51ad-4c7d-a88e-d4e6e3c9a672").withUsername("test1").saved
          val user2 = UserFactory.user().withName("Shachaf", "Smith").withId("bbbbbbbb-51ad-4c7d-a88e-d4e6e3c9a673").withUsername("test").saved

          val uri = uriRepo.save(NormalizedURI.withHash(googleUrl, Some("Google")))

          val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val keep1 = keepRepo.save(Keep(
            title = Some("G1"), userId = user1.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint,
            externalId = ExternalId("cccccccc-286e-4386-8336-da255120b273")))
          val keep2 = keepRepo.save(Keep(
            title = None, userId = user2.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.bookmarkImport, createdAt = t2.plusDays(1),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint,
            externalId = ExternalId("dddddddd-286e-4386-8336-da255120b273")))

          val coll1 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Cooking"), createdAt = t1, externalId = ExternalId("eeeeeeee-51ad-4c7d-a88e-d4e6e3c9a672")))
          val coll2 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Baking"), createdAt = t2, externalId = ExternalId("ffffffff-51ad-4c7d-a88e-d4e6e3c9a673")))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll2.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = coll2.id.get))

          (user1, uri)
        }

        db.readOnlyMaster { implicit s =>
          normalizedURIInterner.getByUri(googleUrl) match {
            case Some(nUri) =>
              nUri === uri
            case None =>
              failure(s"on $googleUrl")
          }
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val request = FakeRequest("POST", path).withBody(Json.obj("url" -> "http://www.google.com"))
        val result = inject[MobilePageController].getPageDetails()(request)

        status(result) must equalTo(OK)
        contentType(result) must beSome("application/json")
        val expected = Json.obj(
          "normalized" -> "http://www.google.com",
          "kept" -> "public",
          "keepId" -> "cccccccc-286e-4386-8336-da255120b273",
          "tags" -> Seq(
            Json.obj("id" -> "eeeeeeee-51ad-4c7d-a88e-d4e6e3c9a672", "name" -> "Cooking"),
            Json.obj("id" -> "ffffffff-51ad-4c7d-a88e-d4e6e3c9a673", "name" -> "Baking")),
          "keepers" -> Seq(
            Json.obj("id" -> "aaaaaaaa-51ad-4c7d-a88e-d4e6e3c9a672", "firstName" -> "Shanee", "lastName" -> "Smith", "pictureName" -> "0.jpg", "username" -> "test1")),
          "keeps" -> 1)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }

    "query extension " in {
      withDb(mobileControllerTestModules: _*) { implicit injector =>
        val path = com.keepit.controllers.mobile.routes.MobilePageController.queryExtension().url
        path === "/m/1/page/extensionQuery"

        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val googleUrl = "http://www.google.com"
        val (user1, uri) = db.readWrite { implicit s =>
          val user1 = UserFactory.user().withName("Shanee", "Smith").withId("aaaaaaaa-51ad-4c7d-a88e-d4e6e3c9a672").withUsername("test1").saved
          val user2 = UserFactory.user().withName("Shachaf", "Smith").withId("bbbbbbbb-51ad-4c7d-a88e-d4e6e3c9a673").withUsername("test").saved

          val uri = uriRepo.save(NormalizedURI.withHash(googleUrl, Some("Google")))

          val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))

          val lib1 = libraryRepo.save(Library(name = "Lib", ownerId = user1.id.get, visibility = LibraryVisibility.SECRET, slug = LibrarySlug("asdf"), memberCount = 1))

          val keep1 = keepRepo.save(Keep(
            title = Some("G1"), userId = user1.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint,
            externalId = ExternalId("cccccccc-286e-4386-8336-da255120b273")))
          val keep2 = keepRepo.save(Keep(
            title = None, userId = user2.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.bookmarkImport, createdAt = t2.plusDays(1),
            visibility = LibraryVisibility.DISCOVERABLE, libraryId = Some(lib1.id.get), inDisjointLib = lib1.isDisjoint,
            externalId = ExternalId("dddddddd-286e-4386-8336-da255120b273")))

          val coll1 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Cooking"), createdAt = t1, externalId = ExternalId("eeeeeeee-51ad-4c7d-a88e-d4e6e3c9a672")))
          val coll2 = collectionRepo.save(Collection(userId = user1.id.get, name = Hashtag("Baking"), createdAt = t2, externalId = ExternalId("ffffffff-51ad-4c7d-a88e-d4e6e3c9a673")))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll2.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = coll2.id.get))

          val user1933 = UserFactory.user().withName("Paul", "Dirac").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a673").withUsername("test").saved
          val user1935 = UserFactory.user().withName("James", "Chadwick").withId("e58be33f-51ad-4c7d-a88e-d4e6e3c9a674").withUsername("test1").saved
          val friends = List(user1933, user1935)

          val now = new DateTime(2013, 5, 31, 4, 3, 2, 1, DEFAULT_DATE_TIME_ZONE)
          friends.zipWithIndex.foreach { case (friend, i) => userConnRepo.save(UserConnection(user1 = user1.id.get, user2 = friend.id.get, createdAt = now.plusDays(i))) }

          (user1, uri)
        }

        db.readOnlyMaster { implicit s =>
          normalizedURIInterner.getByUri(googleUrl) match {
            case Some(nUri) =>
              nUri === uri
            case None =>
              failure(s"on $googleUrl")
          }
        }

        inject[FakeUserActionsHelper].setUser(user1)
        val request = FakeRequest("POST", path).withBody(Json.obj("url" -> "http://www.google.com"))
        val result = inject[MobilePageController].queryExtension(0, 1000)(request)

        status(result) must equalTo(OK)
      }
    }
  }
}

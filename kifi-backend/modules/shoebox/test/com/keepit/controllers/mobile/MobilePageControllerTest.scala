package com.keepit.controllers.mobile

import org.specs2.mutable.Specification

import org.joda.time.DateTime
import com.keepit.common.time._

import com.keepit.search._
import com.keepit.common.controller._
import com.keepit.common.db._
import com.keepit.model._
import com.keepit.test.{ShoeboxApplication, ShoeboxApplicationInjector}

import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import com.keepit.shoebox.FakeShoeboxServiceModule
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.mail.FakeMailModule
import com.keepit.common.analytics.TestAnalyticsModule
import com.keepit.common.store.ShoeboxFakeStoreModule
import com.keepit.common.actor.TestActorSystemModule
import com.keepit.common.healthcheck.FakeAirbrakeModule

class MobilePageControllerTest extends Specification with ShoeboxApplicationInjector {

  val mobileControllerTestModules = Seq(
    FakeShoeboxServiceModule(),
    FakeMailModule(),
    FakeHttpClientModule(),
    TestAnalyticsModule(),
    ShoeboxFakeStoreModule(),
    TestActorSystemModule(),
    FakeAirbrakeModule(),
    FakeActionAuthenticatorModule(),
    FakeSearchServiceClientModule(),
    TestSliderHistoryTrackerModule()
  )

  "mobileController" should {
    "return connected users from the database" in {
      running(new ShoeboxApplication(mobileControllerTestModules:_*)) {
        val path = com.keepit.controllers.mobile.routes.MobilePageController.getPageDetails().toString
        path === "/m/1/page/details"

        val t1 = new DateTime(2013, 2, 14, 21, 59, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val t2 = new DateTime(2013, 3, 22, 14, 30, 0, 0, DEFAULT_DATE_TIME_ZONE)
        val googleUrl = "http://www.google.com"
        val (user1, uri) = db.readWrite {implicit s =>
          val user1 = userRepo.save(User(firstName="Shanee", lastName="Smith", externalId = ExternalId("aaaaaaaa-51ad-4c7d-a88e-d4e6e3c9a672")))
          val user2 = userRepo.save(User(firstName="Shachaf", lastName="Smith", externalId = ExternalId("bbbbbbbb-51ad-4c7d-a88e-d4e6e3c9a673")))

          val uri = uriRepo.save(NormalizedURI.withHash(googleUrl, Some("Google")))

          val url = urlRepo.save(URLFactory(url = uri.url, normalizedUriId = uri.id.get))

          val keep1 = keepRepo.save(Keep(
            title = Some("G1"), userId = user1.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.keeper, createdAt = t1.plusMinutes(3),
            externalId = ExternalId("cccccccc-286e-4386-8336-da255120b273")))
          val keep2 = keepRepo.save(Keep(
            title = None, userId = user2.id.get, url = url.url, urlId = url.id.get,
            uriId = uri.id.get, source = KeepSource.bookmarkImport, createdAt = t2.plusDays(1),
            externalId = ExternalId("dddddddd-286e-4386-8336-da255120b273")))

          val coll1 = collectionRepo.save(Collection(userId = user1.id.get, name = "Cooking", createdAt = t1, externalId = ExternalId("eeeeeeee-51ad-4c7d-a88e-d4e6e3c9a672")))
          val coll2 = collectionRepo.save(Collection(userId = user1.id.get, name = "Baking", createdAt = t2, externalId = ExternalId("ffffffff-51ad-4c7d-a88e-d4e6e3c9a673")))

          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll1.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep1.id.get, collectionId = coll2.id.get))
          keepToCollectionRepo.save(KeepToCollection(keepId = keep2.id.get, collectionId = coll2.id.get))

          (user1, uri)
        }

        db.readOnly {implicit s =>
          uriRepo.getByUri(googleUrl) match {
            case Some(nUri) =>
              nUri === uri
            case None =>
              failure(s"on $googleUrl")
          }
        }

        inject[FakeActionAuthenticator].setUser(user1)
        val request = FakeRequest("POST", path).withJsonBody(Json.obj("url" -> "http://www.google.com"))
        val result = route(request).get

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
              Json.obj("id" -> "aaaaaaaa-51ad-4c7d-a88e-d4e6e3c9a672", "firstName" -> "Shanee", "lastName" -> "Smith", "pictureName" -> "0.jpg")),
            "keeps" -> 1)
        Json.parse(contentAsString(result)) must equalTo(expected)
      }
    }
  }
}

package com.keepit.controllers.internal

import org.specs2.mutable.Specification

import play.api.test.Helpers._
import play.api.test._
import play.api.libs.json.Json

import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import com.keepit.common.controller.{ FakeUserActionsModule, FakeUserActionsHelper }
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizationService

import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class ShoeboxDeepLinkControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  "ShoeboxDeepLinkController" should {
    "createDeepLink" in {
      withDb(FakeUserActionsModule()) { implicit injector =>
        val db = inject[Database]
        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val deepLinkRepo = inject[DeepLinkRepo]
        val normalizationService = inject[NormalizationService]
        var controller = inject[ShoeboxDeepLinkController]

        val (heinlein, niven, uri) = db.readWrite { implicit s =>
          deepLinkRepo.count === 0
          (
            UserFactory.user().withName("Robert", "Heinlein").withUsername("test").saved,
            UserFactory.user().withName("Larry", "Niven").withUsername("test2").saved,
            uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/").get, Some("Google")))
          )
        }

        inject[FakeUserActionsHelper].setUser(heinlein)

        {
          val path = com.keepit.controllers.internal.routes.ShoeboxDeepLinkController.createDeepLink().toString()
          path === "/internal/shoebox/database/createDeepLink"

          val request = FakeRequest("POST", path).withBody(Json.obj(
            "initiator" -> heinlein.id.get.id,
            "recipient" -> niven.id.get.id,
            "uriId" -> uri.id.get.id,
            "locator" -> "/my/location"
          ))
          val result = controller.createDeepLink()(request)
          status(result) must equalTo(OK)
        }

        val deepLinks = db.readOnlyMaster { implicit s => deepLinkRepo.all() }
        deepLinks.size === 1
        val deepLink = deepLinks.head
        deepLink.deepLocator.value === "/my/location"

        db.readOnlyMaster { implicit s => deepLinkRepo.getByLocatorAndUser(deepLink.deepLocator, deepLink.recipientUserId.get) } === deepLink
      }
    }
  }
}

package com.keepit.controllers.ext

import org.specs2.mutable.Specification

import play.api.test.Helpers._
import play.api.test._
import play.api.libs.json.Json

import com.keepit.inject.ApplicationInjector
import com.keepit.test.ShoeboxApplication
import com.keepit.common.controller.FakeActionAuthenticator
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizationService
import scala.Some

class ExtDeepLinkControllerTest extends Specification with ApplicationInjector {

  "ExtDeepLinkController" should {
    "create and get" in {
      running(new ShoeboxApplication()) {
        val db = inject[Database]
        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val deepLinkRepo = inject[DeepLinkRepo]
        val normalizationService = inject[NormalizationService]

        val (heinlein, niven, uri) = db.readWrite {implicit s =>
          deepLinkRepo.count === 0
          (
            userRepo.save(User(firstName = "Robert", lastName = "Heinlein")),
            userRepo.save(User(firstName = "Larry", lastName = "Niven")),
            uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/"), Some("Google")))
          )
        }

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.createDeepLink().toString()
          path === "/internal/shoebox/database/createDeepLink"
          inject[FakeActionAuthenticator].setUser(heinlein)

          val request = FakeRequest("POST", path).withJsonBody(Json.obj(
            "initiator" -> heinlein.id.get.id,
            "recipient" -> niven.id.get.id,
            "uriId" -> uri.id.get.id,
            "locator" -> "/my/location"
          ))
          val result = route(request).get
          status(result) must equalTo(OK)
        }

        val deepLinks = db.readOnly {implicit s => deepLinkRepo.all()}
        deepLinks.size === 1
        val deepLink = deepLinks.head
        deepLink.deepLocator.value === "/my/location"

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path)
          val result = route(request).get
          status(result) must equalTo(SEE_OTHER)
          redirectLocation(result) must equalTo("http://www.google.com")

        }
      }
    }
  }
}

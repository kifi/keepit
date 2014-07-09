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
            uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/").get, Some("Google")))
          )
        }

        inject[FakeActionAuthenticator].setUser(heinlein)

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.createDeepLink().toString()
          path === "/internal/shoebox/database/createDeepLink"

          val request = FakeRequest("POST", path).withJsonBody(Json.obj(
            "initiator" -> heinlein.id.get.id,
            "recipient" -> niven.id.get.id,
            "uriId" -> uri.id.get.id,
            "locator" -> "/my/location"
          ))
          val result = route(request).get
          status(result) must equalTo(OK)
        }

        val deepLinks = db.readOnlyMaster {implicit s => deepLinkRepo.all()}
        deepLinks.size === 1
        val deepLink = deepLinks.head
        deepLink.deepLocator.value === "/my/location"

        db.readOnlyMaster {implicit s => deepLinkRepo.getByLocatorAndUser(deepLink.deepLocator, deepLink.recipientUserId.get)} === deepLink

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path)
          val result = route(request).get
          status(result) must equalTo(SEE_OTHER)
          redirectLocation(result) must equalTo(Some("http://www.google.com"))
        }

        inject[FakeActionAuthenticator].setUser(niven)

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path)
          val result = route(request).get
          status(result) must equalTo(OK)
          contentAsString(result).contains("""window.location = "http://www.google.com";""") === false
        }

        inject[FakeActionAuthenticator].setUser(niven, Set(ExperimentType.MOBILE_REDIRECT))

        {
          val path = com.keepit.controllers.ext.routes.ExtDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path).withHeaders(USER_AGENT -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
          val result = route(request).get
          status(result) must equalTo(OK)
          val content = contentAsString(result)
          contentAsString(result).contains("""window.location = "http://www.google.com";""") === true
        }
      }
    }
  }
}

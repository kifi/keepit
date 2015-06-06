package com.keepit.controllers.email

import org.specs2.mutable.Specification

import play.api.test.Helpers._
import play.api.test._

import com.keepit.test.{ DbInjectionHelper, ShoeboxTestInjector }
import com.keepit.common.controller.{ FakeUserActionsModule, FakeUserActionsHelper }
import com.keepit.model._
import com.keepit.common.db.slick.Database
import com.keepit.normalizer.NormalizationService

import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

class EmailDeepLinkControllerTest extends Specification with ShoeboxTestInjector with DbInjectionHelper {

  "EmailDeepLinkController" should {
    "handle valid requests from various users and devices" in {
      withDb(FakeUserActionsModule()) { implicit injector =>
        val db = inject[Database]
        val userRepo = inject[UserRepo]
        val uriRepo = inject[NormalizedURIRepo]
        val deepLinkRepo = inject[DeepLinkRepo]
        val normalizationService = inject[NormalizationService]
        var controller = inject[EmailDeepLinkController]

        val (heinlein, niven, uri, deepLink) = db.readWrite { implicit s =>
          deepLinkRepo.count === 0
          val heinlein = UserFactory.user().withName("Robert", "Heinlein").withUsername("test").saved
          val niven = UserFactory.user().withName("Larry", "Niven").withUsername("test2").saved
          val uri = uriRepo.save(NormalizedURI.withHash(normalizationService.prenormalize("http://www.google.com/").get, Some("Google")))
          val deepLink = deepLinkRepo.save(DeepLink(
            initiatorUserId = heinlein.id,
            recipientUserId = niven.id,
            uriId = uri.id,
            urlId = None,
            deepLocator = DeepLocator("/my/location")))
          (heinlein, niven, uri, deepLink)
        }

        inject[FakeUserActionsHelper].setUser(heinlein)

        {
          val path = com.keepit.controllers.email.routes.EmailDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"
          val request = FakeRequest("GET", path)
          val result = controller.handle(deepLink.token.value)(request)
          status(result) must equalTo(SEE_OTHER)
          redirectLocation(result) must equalTo(Some("http://www.google.com"))
        }

        inject[FakeUserActionsHelper].setUser(niven)

        {
          val path = com.keepit.controllers.email.routes.EmailDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path)
          val result = controller.handle(deepLink.token.value)(request)
          status(result) must equalTo(OK)
          contentAsString(result).contains("""window.location = "http://www.google.com";""") === false
        }

        inject[FakeUserActionsHelper].setUser(niven, Set(ExperimentType.MOBILE_REDIRECT))

        {
          val path = com.keepit.controllers.email.routes.EmailDeepLinkController.handle(deepLink.token.value).toString()
          path === s"/r/${deepLink.token.value}"

          val request = FakeRequest("GET", path).withHeaders(USER_AGENT -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
          val result = controller.handle(deepLink.token.value)(request)
          status(result) must equalTo(OK)
          val content = contentAsString(result)
          contentAsString(result).contains("""'kifi:/open/my/location'""") === true
        }
      }
    }
  }
}

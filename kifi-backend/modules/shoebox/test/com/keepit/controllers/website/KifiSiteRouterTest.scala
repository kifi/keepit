package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.{ LibraryCommander, UserCommander }
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.{ FakeUserActionsHelper, UserRequest, NonUserRequest }
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.db.Id
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.mobile.MobileKeepsController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model._
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.{ ShoeboxTestInjector, ShoeboxApplication, ShoeboxApplicationInjector }

import org.specs2.mutable.Specification
import org.specs2.matcher.{ Matcher, Expectable }

import play.api.mvc.Result
import play.api.test._
import play.api.test.Helpers._

import scala.concurrent.Future

import securesocial.core.SecureSocial

class KifiSiteRouterTest extends Specification with ShoeboxApplicationInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    FakeCuratorServiceClientModule()
  )

  "KifiSiteRouter" should {
    implicit val context = HeimdalContext.empty

    "route correctly" in {
      running(new ShoeboxApplication(modules: _*)) {
        // Database population
        val (user1, user2) = db.readWrite { implicit session =>
          val u1 = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("abez"), normalizedUsername = "abez"))
          val u2 = userRepo.save(User(firstName = "Léo", lastName = "HasAnAccentInHisName", username = Username("léo1221"), normalizedUsername = "leo"))
          (u1, u2)
        }

        val userCommander = inject[UserCommander]
        userCommander.setUsername(user1.id.get, Username("abez"))
        userCommander.setUsername(user2.id.get, Username("léo1221"))

        val router = inject[KifiSiteRouter]
        val actionsHelper = inject[FakeUserActionsHelper]

        // Custom matchers, TODO: find better home
        case class beRedirect(expectedStatus: Int, expectedUrl: String) extends Matcher[Option[Future[Result]]] {
          def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
            x.value map { resultF =>
              val resStatus = status(resultF)
              if (resStatus != expectedStatus) {
                result(false, "", s"expected status $expectedStatus but was $resStatus", x)
              }
              redirectLocation(resultF) map { resUrl =>
                result(resUrl == expectedUrl, "", s"expected redirect to $expectedUrl but was $resUrl", x)
              } getOrElse result(false, "", "expected redirect to $expectedUrl but was None", x)
            } getOrElse result(false, "", "request did not match any routes", x)
          }
        }
        case class beLoginRedirect(expectedUrl: String) extends Matcher[Option[Future[Result]]] {
          def apply[T <: Option[Future[Result]]](x: Expectable[T]) = {
            x.value map { resultF =>
              val expectedStatus = 303
              val resStatus = status(resultF)
              if (resStatus != expectedStatus) {
                result(false, "", s"expected status $expectedStatus but was $resStatus", x)
              }
              val loginUrl = "/login"
              redirectLocation(resultF) map { resUrl =>
                if (resUrl != loginUrl) {
                  result(false, "", s"expected redirect to $loginUrl but was $resUrl", x)
                } else {
                  val destUrl = session(resultF).get(SecureSocial.OriginalUrlKey)
                  result(destUrl == Some(expectedUrl), "", s"expected destination $expectedUrl in session cookie but was $destUrl", x)
                }
              } getOrElse result(false, "", "expected redirect to $expectedUrl but was None", x)
            } getOrElse result(false, "", "request did not match any routes", x)
          }
        }

        // Username routing
        router.route(NonUserRequest(FakeRequest.apply("GET", "/asdf"))) === Error404

        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez/libraries/following"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez/libraries/invited"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez/libraries1234/invited"))) === Error404
        router.route(NonUserRequest(FakeRequest.apply("GET", "/leo1221"))) === SeeOtherRoute("/l%C3%A9o1221")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1221"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1222"))) === Error404

        userCommander.setUsername(user1.id.get, Username("abe.z1234"))
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) === MovedPermanentlyRoute("/abe.z1234")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abeZ1234/awesome-lib"))) === SeeOtherRoute("/abe.z1234/awesome-lib")

        // Profile routing
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/libraries/following"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/libraries/invited"))) must beAnInstanceOf[Angular]

        // Library routing
        val libraryCommander = inject[LibraryCommander]
        val Right(library) = {
          val libraryRequest = LibraryAddRequest(name = "Awesome Lib", visibility = LibraryVisibility.PUBLISHED, slug = "awesome-lib")
          libraryCommander.addLibrary(libraryRequest, user1.id.get)
        }
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/awesome-lib"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/awesome-lib/whatever?q=weee"))) must beAnInstanceOf[Angular]

        libraryCommander.modifyLibrary(library.id.get, library.ownerId, LibraryModifyRequest(slug = Some("most-awesome-lib")))
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/awesome-lib"))) === MovedPermanentlyRoute("/abe.z1234/most-awesome-lib")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234/most-awesome-lib"))) must beAnInstanceOf[Angular]

        // Fixed Angular routes
        router.route(NonUserRequest(FakeRequest.apply("GET", "/invite"))) === RedirectToLogin("/invite")
        actionsHelper.setUser(user1)
        router.route(UserRequest(FakeRequest.apply("GET", "/invite"), user1.id.get, None, actionsHelper)) must beAnInstanceOf[Angular]

        // /me
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/me")) must beLoginRedirect("/me")
        route(FakeRequest("GET", "/me/libraries/following")) must beLoginRedirect("/me/libraries/following")

        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/me")) must beRedirect(303, "/abez")
        route(FakeRequest("GET", "/me/libraries/invited")) must beRedirect(303, "/abez/libraries/invited")

        // Redirects (logged out)
        actionsHelper.unsetUser
        route(FakeRequest("GET", "/recommendations")) must beLoginRedirect("/")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/connections"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends"))) === RedirectToLogin("/connections")
        route(FakeRequest("GET", "/friends/invite")) must beLoginRedirect("/invite")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends/requests"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends/requests/email"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends/requests/linkedin"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends/requests/facebook"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends/requests/refresh"))) === RedirectToLogin("/connections")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/friends?friend=" + user2.externalId))) === RedirectToLogin("/l%C3%A9o1221?intent=connect")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/invite?friend=" + user2.externalId))) === RedirectToLogin("/l%C3%A9o1221?intent=connect")

        // Redirects (logged in)
        actionsHelper.setUser(user1)
        route(FakeRequest("GET", "/recommendations")) must beRedirect(301, "/")
        router.route(UserRequest(FakeRequest.apply("GET", "/connections"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        route(FakeRequest("GET", "/friends/invite")) must beRedirect(301, "/invite")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends/requests"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends/requests/email"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends/requests/linkedin"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends/requests/facebook"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends/requests/refresh"), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/abez/connections")
        router.route(UserRequest(FakeRequest.apply("GET", "/friends?friend=" + user2.externalId), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/l%C3%A9o1221?intent=connect")
        router.route(UserRequest(FakeRequest.apply("GET", "/invite?friend=" + user2.externalId), user1.id.get, None, actionsHelper)) === SeeOtherRoute("/l%C3%A9o1221?intent=connect")

        { // catching mobile
          val request = FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
          val result = inject[KifiSiteRouter].app("some/path?kma=1")(request)
          status(result) must equalTo(OK)
          contentType(result) must beSome("text/html")
          val resString = contentAsString(result)
          resString.contains("window.location = 'kifi://some/path?kma=1';") === true
          resString.contains("var cleanUrl = '/some/path?kma=1") === true
        }

        { // ignoring flag with no mobile
          val request = FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0")
          val result = inject[KifiSiteRouter].app("some/path?kma=1")(request)
          status(result) must equalTo(404)
        }

        { // ignoring mobile with no flag
          val request = FakeRequest("GET", "/some/path").withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
          val result = inject[KifiSiteRouter].app("some/path")(request)
          status(result) must equalTo(404)
        }
      }
    }
  }
}

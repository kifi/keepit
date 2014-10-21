package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.UserCommander
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.controller.NonUserRequest
import com.keepit.common.crypto.FakeCryptoModule
import com.keepit.common.external.FakeExternalServiceModule
import com.keepit.common.mail.{ EmailAddress, FakeMailModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.store.FakeShoeboxStoreModule
import com.keepit.controllers.mobile.MobileKeepsController
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ User, UserConnectionRepo, UserEmailAddress, UserEmailAddressRepo, UserEmailAddressStates, UserRepo, UserValueName, UserValueRepo, Username }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import play.api.test.Helpers.{ contentType, OK, status }

import play.api.test.Helpers._
import play.api.test._

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class KifiSiteRouterTest extends Specification with ShoeboxTestInjector {

  val modules = Seq(
    FakeExecutionContextModule(),
    FakeMailModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule(),
    FakeSearchServiceClientModule(),
    FakeScrapeSchedulerModule(),
    FakeShoeboxStoreModule(),
    FakeExternalServiceModule(),
    FakeCortexServiceClientModule(),
    FakeScraperServiceClientModule(),
    FakeKeepImportsModule(),
    FakeCryptoModule(),
    FakeCuratorServiceClientModule()
  )

  "KifiSiteRouter" should {

    "route requests correctly" in {
      withDb(modules: _*) { implicit injector =>
        val userCommander = inject[UserCommander]
        val userValueRepo = inject[UserValueRepo]
        val (user1, user2) = db.readWrite { implicit session =>
          val u1 = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Username("abez"), normalizedUsername = "abez"))
          val u2 = userRepo.save(User(firstName = "Léo", lastName = "HasAnAccentInHisName", username = Username("léo1221"), normalizedUsername = "leo"))

          (u1, u2)
        }

        userCommander.setUsername(user1.id.get, Username("abez"))
        userCommander.setUsername(user2.id.get, Username("léo1221"))

        val router = inject[KifiSiteRouter]

        router.route(NonUserRequest(FakeRequest.apply("GET", "/asdf"))) === Error404

        // Username routing
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/leo1221"))) === RedirectRoute("/l%C3%A9o1221")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1221"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1222"))) === Error404

        userCommander.setUsername(user1.id.get, Username("abe.z1234"))
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) === Error404
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abeZ1234/awesome-lib"))) === RedirectRoute("/abe.z1234/awesome-lib")

        1 === 1
      }
    }

    "catching mobile" in {
      withDb(modules: _*) { implicit injector =>
        val request = FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
        val result = inject[KifiSiteRouter].app("some/path?kma=1")(request)
        status(result) must equalTo(OK);
        contentType(result) must beSome("text/html");
        val resString = contentAsString(result)
        resString.contains("kifi:TEST_MODE/some/path?kma=1") === true
      }
    }

    "ignoring flag with no mobile" in {
      withDb(modules: _*) { implicit injector =>
        val request = FakeRequest("GET", "/some/path?kma=1").withHeaders("user-agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:20.0) Gecko/20100101 Firefox/20.0")
        val result = inject[KifiSiteRouter].app("some/path?kma=1")(request)
        status(result) must equalTo(404);
      }
    }

    "ignoring mobile with no flag" in {
      withDb(modules: _*) { implicit injector =>
        val request = FakeRequest("GET", "/some/path").withHeaders("user-agent" -> "Mozilla/5.0 (iPhone; U; CPU iPhone OS 5_1_1 like Mac OS X; en) AppleWebKit/534.46.0 (KHTML, like Gecko) CriOS/19.0.1084.60 Mobile/9B206 Safari/7534.48.3")
        val result = inject[KifiSiteRouter].app("some/path")(request)
        status(result) must equalTo(404);
      }
    }

  }
}

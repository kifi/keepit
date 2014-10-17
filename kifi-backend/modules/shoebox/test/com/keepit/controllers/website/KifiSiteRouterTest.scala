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
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.curator.FakeCuratorServiceClientModule
import com.keepit.model.{ User, UserConnectionRepo, UserEmailAddress, UserEmailAddressRepo, UserEmailAddressStates, UserRepo, UserValueName, UserValueRepo, Username }
import com.keepit.scraper.{ FakeScrapeSchedulerModule, FakeScraperServiceClientModule }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.shoebox.FakeKeepImportsModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.test.FakeRequest

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
          val u1 = userRepo.save(User(firstName = "Abe", lastName = "Lincoln", username = Some(Username("abez"))))
          val u2 = userRepo.save(User(firstName = "Léo", lastName = "HasAnAccentInHisName", username = Some(Username("léo1221"))))

          (u1, u2)
        }

        userCommander.setUsername(user1.id.get, Username("abez"))
        userCommander.setUsername(user2.id.get, Username("léo1221"))

        val router = inject[KifiSiteRouter]

        router.route(NonUserRequest(FakeRequest.apply("GET", "/asdf"))) === Error404

        // Username routing
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/leo1221"))) === RedirectRoute("/léo1221")
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1221"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/léo1222"))) === Error404

        userCommander.setUsername(user1.id.get, Username("abe.z1234"))
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abez"))) === Error404
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abe.z1234"))) must beAnInstanceOf[Angular]
        router.route(NonUserRequest(FakeRequest.apply("GET", "/abeZ1234/awesome-lib"))) === RedirectRoute("/abe.z1234/awesome-lib")

        1 === 1
      }
    }

  }
}

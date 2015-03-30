package com.keepit.commanders.emails.tips

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.{ FakeExecutionContextModule, ExecutionContextModule }
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.EmailToSend
import com.keepit.common.net.{ UserAgent, FakeHttpClientModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ Username, KifiInstallationPlatform, KifiExtVersion, KifiInstallation, KifiInstallationRepo, NotificationCategory, User, UserRepo }
import com.keepit.scraper.FakeScrapeSchedulerModule
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.twirl.api.Html

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class InstallExtensionTipTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeScrapeSchedulerModule(),
    FakeHttpClientModule(),
    FakeSocialGraphModule(),
    FakeHealthcheckModule(),
    FakeGraphServiceModule(),
    FakeCortexServiceClientModule(),
    FakeHeimdalServiceClientModule(),
    FakeSearchServiceClientModule(),
    FakeCacheModule(),
    FakeElizaServiceClientModule(),
    FakeABookServiceClientModule())

  "InstallExtensionTip" should {
    def setup()(implicit injector: Injector) = db.readWrite { implicit rw =>
      val user = inject[UserRepo].save(User(firstName = "Danny", lastName = "Tanner", username = Username("test"), normalizedUsername = "test"))
      val emailToSend = EmailToSend(
        title = "Testing",
        to = Left(user.id.get),
        cc = Seq(SystemEmailAddress.ENG),
        from = SystemEmailAddress.NOTIFICATIONS,
        subject = "hi",
        category = NotificationCategory.System.ADMIN,
        htmlTemplate = Html("")
      )

      (user, emailToSend)
    }

    "does not return html if user has extension installed" in {
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        db.readWrite { implicit rw =>
          inject[KifiInstallationRepo].save(KifiInstallation(userId = user.id.get,
            version = KifiExtVersion(1, 1),
            userAgent = UserAgent("Chrome/26.0.1410.65"),
            platform = KifiInstallationPlatform.Extension
          ))
        }
        val htmlF = inject[InstallExtensionTip].render(emailToSend, user.id.get)
        Await.result(htmlF, Duration(5, "seconds")) must beNone
      }
    }

    // TODO(josh) fix this test when ready
    "returns html if user has not installed extension and joined from library invite" in {
      /*
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        val htmlF = inject[InstallExtensionTip].render(emailToSend, user.id.get)

        val html = Await.result(htmlF, Duration(5, "seconds")).get
      }
      */
      pending("the tip needs to determine if user joined from library invite")
    }
  }

}

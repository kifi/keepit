package com.keepit.commanders.emails.tips

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.cache.FakeCacheModule
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.healthcheck.FakeHealthcheckModule
import com.keepit.common.mail.SystemEmailAddress
import com.keepit.common.mail.template.{ EmailToSend }
import com.keepit.common.db.Id
import com.keepit.common.net.FakeHttpClientModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.cortex.FakeCortexServiceClientModule
import com.keepit.eliza.FakeElizaServiceClientModule
import com.keepit.graph.FakeGraphServiceModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.{ SocialUserInfo, SocialUserInfoRepo, NotificationCategory, User }
import com.keepit.search.FakeSearchServiceClientModule
import com.keepit.social.SocialId
import com.keepit.social.SocialNetworks.{ LINKEDIN, FACEBOOK }
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.twirl.api.Html
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.UserFactory

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ConnectNetworkTipTest extends Specification with ShoeboxTestInjector {
  val modules = Seq(
    FakeExecutionContextModule(),
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

  "ConnectNetworkTip" should {
    def setup()(implicit injector: Injector) = db.readWrite { implicit rw =>
      val user = UserFactory.user().withName("Danny", "Tanner").withUsername("test").saved
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

    def makeSocialUsers(userId: Id[User])(implicit injector: Injector) = {
      db.readWrite { implicit rw =>
        Seq(
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            userId = Some(userId),
            fullName = "Danny Tanner",
            profileUrl = Some("https://fb.com/me"),
            networkType = FACEBOOK,
            socialId = SocialId("fbme"))),
          inject[SocialUserInfoRepo].save(SocialUserInfo(
            userId = Some(userId),
            fullName = "Danny Tanner",
            profileUrl = Some("https://linkedin.com/me"),
            networkType = LINKEDIN,
            socialId = SocialId("linkme")))
        )
      }
    }

    "shows connect facebook tip if not connected to FB" in {
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        val htmlF = inject[ConnectNetworkTip].render(emailToSend, user.id.get)(FACEBOOK)
        val html = Await.result(htmlF, Duration(5, "seconds")).get
        html.body must contain("Connect to Facebook")
      }
    }

    "does not show connect facebook tip if connected to FB" in {
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        makeSocialUsers(user.id.get)
        db.readOnlyMaster { implicit session =>
          inject[SocialUserInfoRepo].getByUser(user.id.get).size === 2
        }
        val htmlF = inject[ConnectNetworkTip].render(emailToSend, user.id.get)(FACEBOOK)
        Await.result(htmlF, Duration(5, "seconds")) must beNone
      }
    }

    "shows connect linkedin tip if not connected to FB" in {
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        val htmlF = inject[ConnectNetworkTip].render(emailToSend, user.id.get)(LINKEDIN)
        val html = Await.result(htmlF, Duration(5, "seconds")).get
        html.body must contain("Connect with LinkedIn")
      }
    }

    "does not show connect linkedin tip if connected to FB" in {
      withDb(modules: _*) { implicit injector =>
        val (user, emailToSend) = setup()
        makeSocialUsers(user.id.get)
        val htmlF = inject[ConnectNetworkTip].render(emailToSend, user.id.get)(LINKEDIN)
        Await.result(htmlF, Duration(5, "seconds")) must beNone
      }
    }
  }

}

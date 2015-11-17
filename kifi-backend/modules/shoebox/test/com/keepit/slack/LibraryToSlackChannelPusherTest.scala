package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class LibraryToSlackChannelPusherTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeClockModule()
  )

  "LibraryToSlackChannelPusher" should {
    "identify and process integrations" in {
      "unmark integrations when finished processing" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            (user, lib, lts)
          }
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessingAt must beNone }

          val resFut = inject[LibraryToSlackChannelPusher].pushToLibrary(lib.id.get)
          val res = Await.result(resFut, Duration.Inf)
          res.size === 1
          res.values.toList === List(false) // integration failed because there is no webhook!

          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessingAt must beNone }
        }
      }
      "push the right message depending on the number of new keeps" in {
        withDb(modules: _*) { implicit injector =>
          var curTime = currentDateTime.minusDays(10)
          inject[FakeClock].setTimeValue(curTime)
          val (user, lib, integration, webhook) = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("Ryan", "Brewster").withUsername("ryanpbrewster").saved
            val lib = LibraryFactory.library().withOwner(user).withName("Random Keeps").withSlug("random-keeps").saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            (user, lib, lts, siw.webhook)
          }

          val titles = Iterator.from(1).map(n => s"panda time <3 #$n")

          // First, no keeps => no message
          curTime = curTime.plusDays(1)
          inject[FakeClock].setTimeValue(curTime)
          Await.result(inject[LibraryToSlackChannelPusher].pushToLibrary(lib.id.get), Duration.Inf)
          slackClient.messagesByWebhook(webhook.url) must beEmpty

          // 2 keeps => 1 msg, 2 lines
          curTime = curTime.plusDays(1)
          inject[FakeClock].setTimeValue(curTime)
          db.readWrite { implicit s => KeepFactory.keeps(2).map(_.withUser(user).withLibrary(lib).withKeptAt(curTime).withTitle(titles.next())).saved }
          Await.result(inject[LibraryToSlackChannelPusher].pushToLibrary(lib.id.get), Duration.Inf)
          slackClient.messagesByWebhook(webhook.url) must haveSize(1)
          slackClient.messagesByWebhook(webhook.url).head.text.lines.size === 2
          slackClient.messagesByWebhook(webhook.url).head.attachments.length === 0 // TODO(ryan): write a test for the attachments-style

          // hella keeps => 1 msg, 1 line (a summary)
          curTime = curTime.plusDays(1)
          inject[FakeClock].setTimeValue(curTime)
          db.readWrite { implicit s => KeepFactory.keeps(20).map(_.withUser(user).withLibrary(lib).withKeptAt(curTime).withTitle(titles.next())).saved }
          Await.result(inject[LibraryToSlackChannelPusher].pushToLibrary(lib.id.get), Duration.Inf)
          slackClient.messagesByWebhook(webhook.url) must haveSize(2)
          slackClient.messagesByWebhook(webhook.url).head.text.lines.size === 1
          slackClient.messagesByWebhook(webhook.url).head.attachments.length === 0

          slackClient.messagesByWebhook(webhook.url).foreach(println)
          1 === 1
        }
      }
    }
  }
}

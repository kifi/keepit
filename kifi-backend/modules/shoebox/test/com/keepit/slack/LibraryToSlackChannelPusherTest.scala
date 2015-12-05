package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.akka.SafeFuture
import com.keepit.common.concurrent.{ FutureHelpers, FakeExecutionContextModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.common.time._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.joda.time.Period
import org.specs2.mutable.SpecificationLike

import scala.concurrent.{ Future, Await }
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
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (user, lib, lts)
          }
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessingAt must beNone }

          val resFut = inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get)
          val res = Await.result(resFut, Duration.Inf)
          res.size === 1
          res.values.toList === List(true)

          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessedAt must beSome }
        }
      }
      "deactivate integrations if the owner is missing permissions" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, user, lib, integration) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(owner).published().saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").withNextPushAt(fakeClock.now).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (owner, user, lib, lts)
          }
          // First time is fine, since they have view permissions
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf).values.toList === List(true)
          // Now we make the lib secret
          db.readWrite { implicit s =>
            libraryRepo.save(lib.copy(visibility = LibraryVisibility.SECRET))
            libToSlackPusher.scheduleLibraryToBePushed(lib.id.get, fakeClock.now)
          }
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf).values.toList === List.empty
          // We hopefully turned off the "bad" integration
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).status === SlackIntegrationStatus.Off }
          1 === 1
        }
      }
      "mark busted integrations as broken" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, lib, integration, webhook) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).published().saved
            KeepFactory.keep().withUser(user).withLibrary(lib).saved

            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (user, lib, lts, siw)
          }
          slackClient.isSlackThrowingAFit = true // pretend Slack hates us and rejects all our webhooks
          // We will try and send, but Slack rejects the webhook
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf).values.toList === List(false)
          // We should have noticed that the webhook is broken and marked it as failed
          // Because the webhook failed, the integration should be marked as broken
          db.readOnlyMaster { implicit s =>
            inject[SlackIncomingWebhookInfoRepo].get(webhook.id.get).lastFailedAt must beSome
            inject[SlackIncomingWebhookInfoRepo].get(webhook.id.get).lastFailure must beSome
            inject[LibraryToSlackChannelRepo].get(integration.id.get).status === SlackIntegrationStatus.Broken
          }
          1 === 1
        }
      }
    }
    "format messages properly" in {
      "push the right message depending on the number of new keeps" in {
        withDb(modules: _*) { implicit injector =>
          fakeClock.setTimeValue(currentDateTime.minusDays(10))
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
          fakeClock += Period.days(1)
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must beEmpty

          // 2 keeps => 1 msg, 2 lines
          fakeClock += Period.days(1)
          db.readWrite { implicit s =>
            KeepFactory.keeps(2).map(_.withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle(titles.next())).saved
            libToSlackPusher.scheduleLibraryToBePushed(lib.id.get, fakeClock.now)
          }
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text.lines.size === 2
          slackClient.pushedMessagesByWebhook(webhook.url).head.attachments.length === 0 // TODO(ryan): write a test for the attachments-style

          // hella keeps => 1 msg, 1 line (a summary)
          fakeClock += Period.days(1)
          db.readWrite { implicit s =>
            KeepFactory.keeps(20).map(_.withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle(titles.next())).saved
            libToSlackPusher.scheduleLibraryToBePushed(lib.id.get, fakeClock.now)
          }
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(2)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text.lines.size === 1
          slackClient.pushedMessagesByWebhook(webhook.url).head.attachments.length === 0

          1 === 1
        }
      }
      "if a message came from slack, make it clear" in {
        withDb(modules: _*) { implicit injector =>
          fakeClock.setTimeValue(currentDateTime.minusDays(10))
          val (user, lib, stm, libToSlack, slackToLib, webhook) = db.readWrite { implicit session =>
            val user = UserFactory.user().withUsername("ryan-kifi").saved
            val lib = LibraryFactory.library().withOwner(user).withName("Random Keeps").withSlug("random-keeps").saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withUsername("ryan-slack").withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").on().withNextIngestionAt(fakeClock.now).saved
            (user, lib, stm, lts, stl, siw.webhook)
          }

          val ch = SlackChannel(SlackChannelId("C123123"), libToSlack.slackChannelName)
          db.readWrite { implicit s => KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("In Kifi").saved }
          fakeClock += Period.days(1)
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text must contain("ryan-kifi")

          slackClient.sayInChannel(stm, ch)("I love sharing links like <http://www.google.com>")
          fakeClock += Period.days(1)
          Await.result(inject[SlackIngestionCommander].ingestAllDue(), Duration.Inf)
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(2)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text must contain("#eng")

          1 === 1
        }
      }
      "format notes, if there are any" in {
        withDb(modules: _*) { implicit injector =>
          fakeClock.setTimeValue(currentDateTime.minusDays(10))
          val (user, lib, integration, webhook) = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("Ryan", "Brewster").withUsername("ryanpbrewster").saved
            val lib = LibraryFactory.library().withOwner(user).withName("Random Keeps").withSlug("random-keeps").saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("Keep Without Note").saved
            KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("Keep With Note").withNote("My [#favorite] keep [#in the world] is this one").saved
            libToSlackPusher.scheduleLibraryToBePushed(lib.id.get, fakeClock.now)
            (user, lib, lts, siw.webhook)
          }
          Await.result(inject[LibraryToSlackChannelPusher].pushUpdatesToSlack(lib.id.get), Duration.Inf)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text must contain("https://www.kifi.com/find?q=tag%3A%22in+the+world%22")
        }
      }
      "let me figure out if weird shit is happening" in {
        withDb(modules: _*) { implicit injector =>
          val z = db.readOnlyMasterAsync { implicit s =>
            Set(1, 2, 3)
          }.flatMap { xs =>
            FutureHelpers.accumulateOneAtATime(xs) { x =>
              Future.successful(
                if (x % 2 == 0) throw new Exception("even = boom")
                else 2 * x
              ).recover { case f => println(f); -1 }
            }
          }.recoverWith {
            case f =>
              println("about to kick a null into the mix")
              Future.failed(null)
          }.recover {
            case failure =>
              println(s"waaaaaaaah, failed because of $failure")
              Map.empty
          }
          val zhere = Await.result(z, Duration.Inf)
          println(zhere)
          1 === 1
        }
      }
    }
  }
}

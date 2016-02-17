package com.keepit.slack

import com.google.inject.Injector
import com.keepit.commanders.{ KeepInterner, RawBookmarkRepresentation }
import com.keepit.common.actor.{ ActorInstance, TestKitSupport }
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import org.joda.time.Period
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

  def ingestFromSlackSurely()(implicit injector: Injector): Unit = {
    inject[ActorInstance[SlackIngestingActor]].ref ! IfYouCouldJustGoAhead
    inject[WatchableExecutionContext].drain()
  }

  def pushUpdatesToSlackSurely(libraryId: Id[Library])(implicit injector: Injector): Map[Id[LibraryToSlackChannel], Boolean] = {
    fakeClock += LibraryToSlackChannelPusher.maxDelayFromKeptAt.toPeriod
    Await.result(inject[LibraryToSlackChannelPusher].findAndPushUpdatesForRipestIntegrations(), Duration.Inf)
  }

  "LibraryToSlackChannelPusher" should {
    "identify and process integrations" in {
      "unmark integrations when finished processing" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (user, lib, lts)
          }
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessingAt must beNone }

          val res = pushUpdatesToSlackSurely(lib.id.get)
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
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").withNextPushAt(fakeClock.now).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (owner, user, lib, lts)
          }
          // First time is fine, since they have view permissions
          pushUpdatesToSlackSurely(lib.id.get).values.toList === List(true)
          // Now we make the lib secret
          db.readWrite { implicit s =>
            libraryRepo.save(lib.copy(visibility = LibraryVisibility.SECRET))
            libToSlackPusher.pushLibraryAtLatest(lib.id.get, fakeClock.now)
          }
          pushUpdatesToSlackSurely(lib.id.get).values.toList === List.empty
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

            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            (user, lib, lts, siw)
          }
          slackClient.isSlackThrowingAFit = true // pretend Slack hates us and rejects all our webhooks
          // We will try and send, but Slack rejects the webhook
          pushUpdatesToSlackSurely(lib.id.get).values.toList === List(false)
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
      "do not activate integrations that are turned off" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, lib, lts) = db.readWrite { implicit s =>
            val owner = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(owner).saved
            val lts = LibraryToSlackChannelFactory.lts().withLibrary(lib).saved
            (owner, lib, lts)
          }

          db.readWrite { implicit s => inject[LibraryToSlackChannelRepo].save(lts.withStatus(SlackIntegrationStatus.Off)) }
          libToSlackPusher.schedule(lib.id.get)
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(lts.id.get).status === SlackIntegrationStatus.Off }
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
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            (user, lib, lts, siw.webhook)
          }

          val titles = Iterator.from(1).map(n => s"panda time <3 #$n")

          // First, no keeps => no message
          pushUpdatesToSlackSurely(lib.id.get)
          slackClient.pushedMessagesByWebhook(webhook.url) must beEmpty

          // 2 keeps => 1 msg, 2 lines
          fakeClock += Period.days(1)
          db.readWrite { implicit s =>
            KeepFactory.keeps(2).map(_.withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle(titles.next())).saved
            libToSlackPusher.pushLibraryAtLatest(lib.id.get, fakeClock.now)
          }
          pushUpdatesToSlackSurely(lib.id.get)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text.lines.size === 4
          slackClient.pushedMessagesByWebhook(webhook.url).head.attachments.length === 0 // TODO(ryan): write a test for the attachments-style

          // hella keeps => 1 msg, 1 line (a summary)
          fakeClock += Period.days(1)
          db.readWrite { implicit s =>
            KeepFactory.keeps(20).map(_.withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle(titles.next())).saved
            libToSlackPusher.pushLibraryAtLatest(lib.id.get, fakeClock.now)
          }
          pushUpdatesToSlackSurely(lib.id.get)
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
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withUsername("ryan-slack").withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").on().withNextIngestionAt(fakeClock.now).saved
            (user, lib, stm, lts, stl, siw.webhook)
          }

          val ch = SlackChannelIdAndName(SlackChannelId("C123123"), libToSlack.slackChannelName)
          db.readWrite { implicit s => KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("In Kifi").saved }
          pushUpdatesToSlackSurely(lib.id.get)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text must contain(user.externalId.id)

          slackClient.sayInChannel(stm.slackUserId, stm.slackUsername, stm.slackTeamId, stm.token, ch)("I love sharing links like <http://www.google.com>")
          ingestFromSlackSurely()
          inject[WatchableExecutionContext].drain()

          val msgsInChannelBeforePush = slackClient.pushedMessagesByWebhook(webhook.url).size
          // Manually trigger a push by adding a new keep
          inject[KeepInterner].internRawBookmark(RawBookmarkRepresentation(url = "http://www.trigger.io"), user.id.get, lib, KeepSource.keeper)

          pushUpdatesToSlackSurely(lib.id.get)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(msgsInChannelBeforePush + 1) // only one message, despite two new keeps
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
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannelName("#eng").saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("Keep Without Note").saved
            KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).withTitle("Keep With Note").withNote("My [#favorite] keep [#in the world] is this one").saved
            libToSlackPusher.pushLibraryAtLatest(lib.id.get, fakeClock.now)
            (user, lib, lts, siw.webhook)
          }
          pushUpdatesToSlackSurely(lib.id.get)
          slackClient.pushedMessagesByWebhook(webhook.url) must haveSize(1)
          slackClient.pushedMessagesByWebhook(webhook.url).head.text must contain("https://www.kifi.com/find?q=tag%3A%22in+the+world%22")
        }
      }
    }
  }
}

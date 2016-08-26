package com.keepit.slack

import com.google.inject.Injector
import com.keepit.common.actor.{ ActorInstance, TestKitSupport }
import com.keepit.common.concurrent.{ FakeExecutionContextModule, WatchableExecutionContext }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.KeepFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackChannelFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead
import org.specs2.mutable.SpecificationLike

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

  def pushUpdatesToSlackSurely(libraryId: Id[Library])(implicit injector: Injector): Unit = {
    db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].getByIds(Set.empty) }
    inject[LibraryToSlackChannelPusher].schedule(Set(libraryId))
    inject[ActorInstance[SlackPushingActor]].ref ! IfYouCouldJustGoAhead
    Thread.sleep(20) // Have to manually wait long enough for the actor to pull tasks and then queue them
    inject[WatchableExecutionContext].drain()
  }

  "LibraryToSlackChannelPusher" should {
    args(skipAll = true)
    "identify and process integrations" in {
      "unmark integrations when finished processing" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team().saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel(channel).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannel(channel).saved
            (user, lib, lts)
          }
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(integration.id.get).lastProcessingAt must beNone }

          pushUpdatesToSlackSurely(lib.id.get)

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
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel(channel).withNextPushAt(fakeClock.now).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannel(channel).saved
            (owner, user, lib, lts)
          }
          // First time is fine, since they have view permissions
          pushUpdatesToSlackSurely(lib.id.get)
          // Now we make the lib secret
          db.readWrite { implicit s =>
            libraryRepo.save(lib.copy(visibility = LibraryVisibility.SECRET))
            libToSlackPusher.pushLibraryAtLatest(lib.id.get, fakeClock.now)
          }
          pushUpdatesToSlackSurely(lib.id.get)
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
            KeepFactory.keep().withUser(user).withLibrary(lib).withKeptAt(fakeClock.now).saved

            val slackTeam = SlackTeamFactory.team().saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel(channel).withLastActivatedAt(fakeClock.now minusDays 5).saved
            val siw = SlackIncomingWebhookFactory.webhook().withMembership(stm).withChannel(channel).saved
            (user, lib, lts, siw)
          }
          slackClient.isSlackThrowingAFit = true // pretend Slack hates us and rejects all our webhooks
          // We will try and send, but Slack rejects the webhook
          pushUpdatesToSlackSurely(lib.id.get)
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
          libToSlackPusher.schedule(Set(lib.id.get))
          db.readOnlyMaster { implicit s => inject[LibraryToSlackChannelRepo].get(lts.id.get).status === SlackIntegrationStatus.Off }
        }
      }
    }
  }
}

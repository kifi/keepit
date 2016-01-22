package com.keepit.slack

import com.google.inject.Injector
import com.keepit.common.actor.{ ActorInstance, TestKitSupport }
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead

class SlackIngestionTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
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

  "SlackIngestionCommander" should {
    "create keeps for integrations that need to be processed" in {
      "create keeps and leave the integrations in a good state" in {
        withDb(modules: _*) { implicit injector =>
          val now = inject[FakeClock].now.minusMinutes(1)
          val (user, lib, stm, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").withNextIngestionAt(now).on().saved
            (user, lib, stm, stl)
          }
          db.readOnlyMaster { implicit s =>
            val x = inject[SlackChannelToLibraryRepo].get(integration.id.get)
            x.lastIngestingAt must beNone
            x.lastIngestedAt must beNone
            ktlRepo.getCountByLibraryId(lib.id.get) === 0
          }

          val ch = SlackChannelIdAndName(SlackChannelId("C123123"), integration.slackChannelName)
          slackClient.sayInChannel(stm.slackUserId, stm.slackUsername, stm.slackTeamId, stm.token, ch)("<http://www.google.com|Google>")
          ingestFromSlackSurely()

          db.readOnlyMaster { implicit s =>
            val x = inject[SlackChannelToLibraryRepo].get(integration.id.get)
            x.lastIngestingAt must beNone
            x.lastIngestedAt must beSome
            ktlRepo.getCountByLibraryId(lib.id.get) === 1
          }
          1 === 1
        }
      }
      "successfully extract multiple links" in {
        withDb(modules: _*) { implicit injector =>
          var now = inject[FakeClock].now.minusMinutes(1)
          val (user, lib, stm, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").withNextIngestionAt(now).on().saved
            (user, lib, stm, stl)
          }

          val msgsAndCounts = Seq(
            "no link here, buddy" -> 0,
            "slack chooses the links: http://www.kifi.com" -> 0,
            "and those links look like this: <http://www.google.com/>" -> 1,
            "or like this: <http://www.yahoo.com|Yahoo>" -> 1,
            "and there can be multiple: <http://www.microsoft.com|Microsoft> <http://www.kifi.com|Kifi>" -> 2
          )

          val ch = SlackChannelIdAndName(SlackChannelId("C123123"), integration.slackChannelName)
          for ((msg, expectedLinkCount) <- msgsAndCounts) {
            now = now.plusHours(2)
            inject[FakeClock].setTimeValue(now)
            val preIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            slackClient.sayInChannel(stm.slackUserId, stm.slackUsername, stm.slackTeamId, stm.token, ch)(msg)
            ingestFromSlackSurely()
            val postIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            (postIngestCount === preIngestCount + expectedLinkCount).setMessage(s"got ${postIngestCount - preIngestCount} links out of $msg")
          }
          1 === 1
        }
      }
    }
  }
}

package com.keepit.slack

import com.google.inject.Injector
import com.keepit.common.actor.{ ActorInstance, TestKitSupport }
import com.keepit.common.concurrent.{ WatchableExecutionContext, FakeExecutionContextModule }
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibrarySpace.OrganizationSpace
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackChannelFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.joda.time.Period
import org.specs2.mutable.SpecificationLike
import com.kifi.juggle.ConcurrentTaskProcessingActor.IfYouCouldJustGoAhead

import scala.util.Random

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

  args(skipAll = true)

  "SlackIngestionCommander" should {
    "create keeps for integrations that need to be processed" in {
      "create keeps and leave the integrations in a good state" in {
        withDb(modules: _*) { implicit injector =>
          val now = inject[FakeClock].now.minusMinutes(1)
          val (user, lib, channel, stm, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team().withOrg(org).saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).withScopes(SlackAuthScope.ingest).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel(channel).withNextIngestionAt(now).on().saved
            (user, lib, channel, stm, stl)
          }
          db.readOnlyMaster { implicit s =>
            val x = inject[SlackChannelToLibraryRepo].get(integration.id.get)
            x.lastIngestingAt must beNone
            x.lastIngestedAt must beNone
            ktlRepo.getCountByLibraryId(lib.id.get) === 0
          }

          slackClient.sayInChannel(stm.slackUserId, stm.slackUsername.get, stm.slackTeamId, stm.token, channel.idAndName)("<http://www.google.com|Google>")
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
          val (user, lib, channel, stm, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val blacklist = ClassFeature.Blacklist(Seq(
              ClassFeature.BlacklistEntry(Some(user.externalId), Some(now), "github.com/kifi"),
              ClassFeature.BlacklistEntry(Some(user.externalId), Some(now), "*.corp.google.com")
            ))
            val org = OrganizationFactory.organization().withOwner(user).withSettings(Map(ClassFeature.SlackIngestionDomainBlacklist -> blacklist)).saved
            val lib = LibraryFactory.library().withOwner(user).withOrganization(org).saved
            val slackTeam = SlackTeamFactory.team().withOrg(org).saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).withScopes(SlackAuthScope.ingest).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel(channel).withNextIngestionAt(now).on().saved
            (user, lib, channel, stm, stl)
          }

          val msgsAndCounts = Seq(
            "no link here, buddy" -> 0,
            "slack chooses the links: http://www.kifi.com" -> 0,
            "and those links look like this: <http://www.google.com/>" -> 1,
            "or like this: <http://www.yahoo.com|Yahoo>" -> 1,
            "and there can be multiple: <http://www.microsoft.com|Microsoft> <http://www.kifi.com|Kifi>" -> 2,
            "and even blacklisted links: <https://www.github.com/kifi/commits|Kifi commits> <https://secure.corp.google.com/page.html> <http://notblacklisted.com>" -> 1
          )

          for ((msg, expectedLinkCount) <- msgsAndCounts) {
            now = now.plusHours(2)
            inject[FakeClock].setTimeValue(now)
            val preIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            slackClient.sayInChannel(stm.slackUserId, stm.slackUsername.get, stm.slackTeamId, stm.token, channel.idAndName)(msg)
            ingestFromSlackSurely()
            val postIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            (postIngestCount === preIngestCount + expectedLinkCount).setMessage(s"got ${postIngestCount - preIngestCount} links out of $msg")
          }
          1 === 1
        }
      }
      "ignore links from bot users" in {
        withDb(modules: _*) { implicit injector =>
          val (teamId, user, bot, lib, channel, integration) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).withOrganization(org).saved
            val slackTeam = SlackTeamFactory.team().withKifiBot(SlackUserId("B4242"), SlackBotAccessToken("LETMEIN")).saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val userMembership = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).withUsername("ryanpbrewster").withScopes(SlackAuthScope.ingest).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(userMembership).withLibrary(lib).withChannel(channel).withNextIngestionAt(fakeClock.now).on().saved
            val slackUser = (userMembership.slackUserId, userMembership.slackUsername.get, userMembership.token.get)
            val kifiBot = (slackTeam.kifiBot.get.userId, SlackUsername("Kifi"), slackTeam.kifiBot.get.token)
            (slackTeam.slackTeamId, slackUser, kifiBot, lib, channel, stl)
          }

          val msgs = Iterator.continually(s"I love <https://www.${RandomStringUtils.randomAlphabetic(10)}.com|this random link>!")
          for (sender @ (userId, username, token) <- Random.shuffle(Seq.fill(10)(user) ++ Seq.fill(10)(bot)).take(10)) {
            fakeClock += Period.hours(2)
            val preIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            val msg = msgs.next()
            slackClient.sayInChannel(userId, username, teamId, Some(token), channel.idAndName)(msg)
            ingestFromSlackSurely()
            val postIngestCount = db.readOnlyMaster { implicit s => ktlRepo.getCountByLibraryId(lib.id.get) }
            val expectedLinkCount = if (sender == user) 1 else 0
            (postIngestCount - preIngestCount === expectedLinkCount).setMessage(s"got ${postIngestCount - preIngestCount} links out of $msg")
          }
          1 === 1
        }
      }
    }
  }
}

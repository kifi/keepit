package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.common.time._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.util.Random

class SlackTeamMembershipRepoTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeClockModule()
  )
  def rand(lo: Int, hi: Int) = lo + Random.nextInt(hi - lo + 1)

  "SlackTeamMebershipRepo" should {
    "get ripe for personal digests" in {
      withDb(modules: _*) { implicit injector =>
        db.readWrite { implicit s =>
          val now = fakeClock.now

          // These do not have the experiment, so we should never get results from these teams
          val garbageTeams = (1 to 10).map(_ => SlackTeamFactory.team().withNoPersonalDigestsUntil(now plusHours rand(-100, 100)).saved)
          val garbageMemberships = garbageTeams.flatMap(team => (1 to 10).map(_ => SlackTeamMembershipFactory.membership().withTeam(team).withNextPersonalDigestAt(now plusHours rand(-100, 100)).saved))

          val teams = (1 to 10).map(_ => SlackTeamFactory.team().withNoPersonalDigestsUntil(now plusHours rand(-100, 100)).saved)
          val memberships = teams.flatMap(team => (1 to 10).map(_ => SlackTeamMembershipFactory.membership().withTeam(team).withNextPersonalDigestAt(now plusHours rand(-100, 100)).saved))

          val expected = {
            val legalTeams = teams.filter(t => t.noPersonalDigestsUntil.exists(time => time isBefore now)).map(_.slackTeamId).toSet
            val legalMemberships = memberships.filter(m => legalTeams.contains(m.slackTeamId) && m.nextPersonalDigestAt.exists(time => time isBefore now))
            val bestByTeam = legalMemberships.groupBy(_.slackTeamId).mapValues(_.sortBy(m => (m.nextPersonalDigestAt.get, m.id.get)).head)
            bestByTeam.values.toList.sortBy(m => (m.nextPersonalDigestAt.get, m.id.get)).map(_.id.get).take(10)
          }
          val actual = {
            inject[SlackTeamMembershipRepo].getRipeForPersonalDigest(limit = 10, overrideProcessesOlderThan = now, now = now, vipTeams = teams.map(_.slackTeamId).toSet).toList
          }
          actual === expected
        }
        1 === 1
      }
    }
  }
}

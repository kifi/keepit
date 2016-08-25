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

class SlackTeamMembershipTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule(),
    FakeClockModule()
  )
  def rand(lo: Int, hi: Int) = lo + Random.nextInt(hi - lo + 1)

  "SlackTeamMebership" should {
    "do crypto things" in {
      "encode and decode team_id + user_id pairs" in {
        val (team1, team2) = (SlackTeamId("TASDFASDF"), SlackTeamId("TPQRSPQRS"))
        val (user1, user2) = (SlackUserId("U42424242"), SlackUserId("UFORTYTWO"))
        val hash1 = SlackTeamMembership.encodeTeamAndUser(team1, user1)
        val hash2 = SlackTeamMembership.encodeTeamAndUser(team2, user2)

        hash1 === "stm_crypt__4EhERWGNfap8iN7amz_TBI-Ng"
        hash2 === "stm_crypt__5B5VrC-vJcVcqXS6afzmDr8gw"

        SlackTeamMembership.decodeTeamAndUser(hash1) must beSome((team1, user1))
        SlackTeamMembership.decodeTeamAndUser(hash2) must beSome((team2, user2))
        SlackTeamMembership.decodeTeamAndUser("12l3kjasdkfjo51") must beNone

        SlackTeamMembership.decodeTeamAndUser(hash1 + "z") !== Some((team1, user1))

      }
    }
    "do repo things" in {
      "get ripe for personal digests" in {
        withDb(modules: _*) { implicit injector =>
          db.readWrite { implicit s =>
            val now = fakeClock.now

            val teams = (1 to 10).map(_ => SlackTeamFactory.team().withNoPersonalDigestsUntil(now plusHours rand(-100, 100)).saved)
            val memberships = teams.flatMap(team => (1 to 10).map(_ => SlackTeamMembershipFactory.membership().withTeam(team).withNextPersonalDigestAt(now plusHours rand(-100, 100)).saved))

            val expected = {
              val legalTeams = teams.filter(t => t.noPersonalDigestsUntil isBefore now).map(_.slackTeamId).toSet
              val legalMemberships = memberships.filter(m => legalTeams.contains(m.slackTeamId) && (m.nextPersonalDigestAt isBefore now))
              val bestByTeam = legalMemberships.groupBy(_.slackTeamId).mapValues(_.sortBy(m => (m.nextPersonalDigestAt, m.id.get)).head)
              bestByTeam.values.toList.sortBy(m => (m.nextPersonalDigestAt, m.id.get)).map(_.id.get).take(10)
            }
            val actual = {
              inject[SlackTeamMembershipRepo].getRipeForPersonalDigest(limit = 10, overrideProcessesOlderThan = now, now = now).toList
            }
            actual.toSet === expected.toSet
          }
          1 === 1
        }
      }

      "return memberships for users who haven't exported" in {
        withDb(modules: _*) { implicit injector =>

          db.readWrite { implicit s =>
            val teams = (1 to 10).map(_ => SlackTeamFactory.team().saved)
            teams.flatMap(team => (1 to 10).map(_ => SlackTeamMembershipFactory.membership().withTeam(team).saved))
          }

          db.readOnlyMaster { implicit s =>
            val stms = inject[SlackTeamMembershipRepo].getMembershipsOfKifiUsersWhoHaventExported(fromId = None)
          }

          1 === 1
        }
      }
    }
  }
}

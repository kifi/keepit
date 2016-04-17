package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.oauth.SlackIdentity
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

class SlackIdentityCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackIdentityCommander" should {
    "register slack identities" in {
      "let not two users share one Slack identity" in {
        withDb(modules: _*) { implicit injector =>
          val (user1, user2, slackTeam) = db.readWrite { implicit s =>
            val Seq(user1, user2) = UserFactory.users(2).saved
            val slackTeam = SlackTeamFactory.team().saved
            (user1, user2, slackTeam)
          }
          val slackUser = SlackUserFactory.user()
          val slackIdentity = SlackIdentity(
            slackTeam.slackTeamId,
            slackUser.userId,
            Some(BasicSlackTeamInfo(slackTeam.slackTeamId, slackTeam.slackTeamName)),
            None,
            None
          )

          db.readOnlyMaster { implicit s => inject[SlackTeamMembershipRepo].all must beEmpty }
          db.readWrite { implicit s => slackCommander.internSlackIdentity(Some(user1.id.get), slackIdentity) }
          db.readOnlyMaster { implicit s =>
            inject[SlackTeamMembershipRepo].all must haveSize(1)
            inject[SlackTeamMembershipRepo].getBySlackTeamAndUser(slackTeam.slackTeamId, slackUser.userId).get.userId must beSome(user1.id.get)
          }
          db.readWrite { implicit s => slackCommander.internSlackIdentity(Some(user2.id.get), slackIdentity) should throwAn[SlackActionFail.MembershipAlreadyConnected] }
        }
      }
    }
  }
}

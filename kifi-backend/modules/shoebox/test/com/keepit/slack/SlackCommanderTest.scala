package com.keepit.slack

import java.lang.IllegalStateException

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.apache.commons.lang3.RandomStringUtils
import org.specs2.mutable.SpecificationLike

class SlackCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackCommander" should {
    "register slack authorizations" in {
      "let not two users share one Slack auth" in {
        withDb(modules: _*) { implicit injector =>
          val (user1, user2, slackTeam) = db.readWrite { implicit s =>
            val Seq(user1, user2) = UserFactory.users(2).saved
            val slackTeam = SlackTeamFactory.team().saved
            (user1, user2, slackTeam)
          }
          val slackUser = SlackUserFactory.user()
          val ident = SlackIdentifyResponse("http://www.rando.slack.com/", slackTeam.slackTeamName, slackUser.username, slackTeam.slackTeamId, slackUser.userId)
          val auth = SlackAuthorizationResponse(SlackAccessToken(RandomStringUtils.randomAlphanumeric(30)), SlackAuthScope.newPush, slackTeam.slackTeamName, slackTeam.slackTeamId, None)

          db.readOnlyMaster { implicit s => inject[SlackTeamMembershipRepo].all must beEmpty }
          slackCommander.registerAuthorization(Some(user1.id.get), auth, ident)
          db.readOnlyMaster { implicit s =>
            inject[SlackTeamMembershipRepo].all must haveSize(1)
            inject[SlackTeamMembershipRepo].getBySlackTeamAndUser(slackTeam.slackTeamId, slackUser.userId).get.userId must beSome(user1.id.get)
          }
          slackCommander.registerAuthorization(Some(user2.id.get), auth, ident) should throwAn[IllegalStateException]
        }
      }
    }
  }
}

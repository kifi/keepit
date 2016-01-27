package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SlackTeamCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackTeamCommander" should {
    "sync public channels correctly" in {
      withDb(modules: _*) { implicit injector =>
        val (user, org, slackTeam) = db.readWrite { implicit s =>
          val user = UserFactory.user().saved
          val org = OrganizationFactory.organization().withOwner(user).saved
          val slackTeam = SlackTeamFactory.team().withOrg(org).saved
          val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
          (user, org, slackTeam)
        }
        val (_, libByChannel) = Await.result(slackTeamCommander.syncPublicChannels(user.id.get, slackTeam.slackTeamId), Duration.Inf)

        libByChannel.foreach { case (ch, lib) => lib must beRight }
        libByChannel.values.map(_.right.get).filter(_.kind == LibraryKind.SYSTEM_ORG_GENERAL) must haveSize(1)

        db.readOnlyMaster { implicit s =>
          libraryRepo.getBySpace(org.id.get) must haveSize(libByChannel.size) // we created one library per channel
          val List(orgGeneral) = libraryRepo.getBySpaceAndKind(org.id.get, LibraryKind.SYSTEM_ORG_GENERAL).toList
          orgGeneral.name === "#general"
        }
        1 === 1
      }
    }
  }
}

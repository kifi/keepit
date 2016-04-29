package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackChannelFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike

class SlackIntegrationCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackIntegrationCommander" should {
    "create new integrations" in {
      "handle multiple integrations for a single library" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, slackTeam, stm, siw1, siw2) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team().withOrg(org).saved
            val channel1 = SlackChannelFactory.channel().withTeam(slackTeam).withName("#1").saved
            val channel2 = SlackChannelFactory.channel().withTeam(slackTeam).withName("#2").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).withScopes(SlackAuthScope.integrationSetup).saved
            val siw1 = SlackIncomingWebhookFactory.webhook().withChannel(channel1).withMembership(stm).saved
            val siw2 = SlackIncomingWebhookFactory.webhook().withChannel(channel2).withMembership(stm).saved
            (user, lib, slackTeam, stm, siw1, siw2)
          }

          slackIntegrationCommander.setupIntegrations(user.id.get, lib.id.get, siw1.id.get)
          slackIntegrationCommander.setupIntegrations(user.id.get, lib.id.get, siw2.id.get)

          val slackInfo = db.readOnlyMaster { implicit s =>
            slackInfoCommander.getFullSlackInfoForLibraries(user.id.get, Set(lib.id.get))
          }

          val orgPubId = Organization.publicId(slackTeam.organizationId.get)(inject[PublicIdConfiguration])
          slackInfo.get(lib.id.get).foreach(_.integrations.foreach { integration =>
            integration.space === ExternalLibrarySpace.fromOrganizationId(orgPubId)
          })
          slackInfo.get(lib.id.get).map(_.integrations.length) must beSome(2)
        }
      }
    }
    "delete /modify existing integrations" in {
      "delete integrations" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, libToSlack, slackToLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team().withOrg(org).saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel(channel).on.saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel(channel).saved

            (user, lib, lts, stl)
          }
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].aTonOfRecords.map(_.id.get) === Seq(libToSlack.id.get)
            inject[SlackChannelToLibraryRepo].aTonOfRecords.map(_.id.get) === Seq(slackToLib.id.get)
          }
          val delRequest = SlackIntegrationDeleteRequest(user.id.get, libToSlack = Set(libToSlack.id.get), slackToLib = Set(slackToLib.id.get))
          slackIntegrationCommander.deleteIntegrations(delRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].aTonOfRecords.filter(_.isActive) === Seq.empty
            inject[SlackChannelToLibraryRepo].aTonOfRecords.filter(_.isActive) === Seq.empty
          }
          1 === 1
        }
      }
      "don't let randos mess with my stuff" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, member, org, lib, libToSlack) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
            val lib = LibraryFactory.library().withOwner(owner).saved
            val slackTeam = SlackTeamFactory.team().saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved

            val stm = SlackTeamMembershipFactory.membership().withUser(owner).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel(channel).on.saved

            (owner, member, org, lib, lts)
          }
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].aTonOfRecords.map(_.id.get) === Seq(libToSlack.id.get)
          }

          // At first, if Member tries to modify they fail because they don't have access (the integration is in a personal space)
          val delRequest = SlackIntegrationDeleteRequest(member.id.get, libToSlack = Set(libToSlack.id.get), slackToLib = Set.empty)
          slackIntegrationCommander.deleteIntegrations(delRequest) must beFailedTry
          1 === 1
        }
      }
    }
  }
}

package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackIncomingWebhookInfoFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json

class SlackCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackCommander" should {
    "create new integrations" in {
      "handle multiple integrations for a single library" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, stm, siw1, siw2) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved

            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val Seq(siw1, siw2) = SlackIncomingWebhookFactory.webhooks(2).map(_.withMembership(stm).saved)
            (user, lib, stm, siw1, siw2)
          }
          val ident = SlackIdentifyResponse(
            url = "https://www.whatever.com",
            teamName = stm.slackTeamName,
            userName = stm.slackUsername,
            teamId = stm.slackTeamId,
            userId = stm.slackUserId
          )
          slackCommander.setupIntegrations(user.id.get, lib.id.get, siw1.webhook, ident)
          slackCommander.setupIntegrations(user.id.get, lib.id.get, siw2.webhook, ident)

          val slackInfo = db.readOnlyMaster { implicit s =>
            slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib.id.get))
          }
          slackInfo.get(lib.id.get).map(_.integrations.length) must beSome(2)
        }
      }
    }
    "serve up a user's library integrations" in {
      "group integrations by channel" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib1, lib2) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val Seq(lib1, lib2) = LibraryFactory.libraries(2).map(_.withOwner(user)).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib2).withChannel("#a").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib2).withChannel("#b").saved

            (user, lib1, lib2)
          }
          val slackInfo = slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib1.id.get, lib2.id.get))

          slackInfo.get(lib1.id.get).map(_.integrations.length) must beSome(1)
          slackInfo.get(lib2.id.get).map(_.integrations.length) must beSome(2)
        }
      }
      "handle really dumb edge-cases correctly" in {
        withDb(modules: _*) { implicit injector =>
          // For instance, suppose you are a member of two different slack teams with the same name: "kifi" and "kifi"
          // in both teams there is an #eng channel
          // you subscribe a library to both of these channels
          val (user, lib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam1 = SlackTeamFactory.team().copy(teamName = SlackTeamName("kifi"))
            val slackTeam2 = SlackTeamFactory.team().copy(teamName = SlackTeamName("kifi"))

            val stm1 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam1).saved
            val stm2 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam2).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm1).withLibrary(lib).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm2).withLibrary(lib).withChannel("#eng").saved

            (user, lib)
          }
          val slackInfo = slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib.id.get))
          // Uncomment to visually inspect the slack info
          // println(Json.prettyPrint(Json.toJson(slackInfo(lib.id.get))))
          slackInfo.get(lib.id.get).map(_.integrations.length) must beSome(2)
        }
      }
    }
    "modify/delete existing integrations" in {
      "turn integrations on/off" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, libToSlack, slackToLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (user, lib, lts, stl)
          }
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].all
            val stls = inject[SlackChannelToLibraryRepo].all
            ltss.map(_.id.get) === Seq(libToSlack.id.get)
            stls.map(_.id.get) === Seq(slackToLib.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.On }
            stls.foreach { stl => stl.status === SlackIntegrationStatus.Off }
          }
          val modRequest = SlackIntegrationModifyRequest(
            user.id.get,
            libToSlack = Map(libToSlack.id.get -> SlackIntegrationModification(status = Some(SlackIntegrationStatus.Off))),
            slackToLib = Map(slackToLib.id.get -> SlackIntegrationModification(status = Some(SlackIntegrationStatus.On)))
          )
          slackCommander.modifyIntegrations(modRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].all
            val stls = inject[SlackChannelToLibraryRepo].all
            ltss.map(_.id.get) === Seq(libToSlack.id.get)
            stls.map(_.id.get) === Seq(slackToLib.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.Off }
            stls.foreach { stl => stl.status === SlackIntegrationStatus.On }
          }
          1 === 1
        }
      }
      "move integrations to different spaces" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, org, libToSlack, slackToLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val org = OrganizationFactory.organization().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (user, lib, org, lts, stl)
          }
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].all
            val stls = inject[SlackChannelToLibraryRepo].all
            ltss.map(_.id.get) === Seq(libToSlack.id.get)
            stls.map(_.id.get) === Seq(slackToLib.id.get)
            ltss.foreach { lts => lts.space === LibrarySpace.fromUserId(user.id.get) }
            stls.foreach { stl => stl.space === LibrarySpace.fromUserId(user.id.get) }
          }
          val modRequest = SlackIntegrationModifyRequest(
            user.id.get,
            libToSlack = Map(libToSlack.id.get -> SlackIntegrationModification(space = Some(LibrarySpace.fromOrganizationId(org.id.get)))),
            slackToLib = Map(slackToLib.id.get -> SlackIntegrationModification(space = Some(LibrarySpace.fromOrganizationId(org.id.get))))
          )
          slackCommander.modifyIntegrations(modRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].all
            val stls = inject[SlackChannelToLibraryRepo].all
            ltss.map(_.id.get) === Seq(libToSlack.id.get)
            stls.map(_.id.get) === Seq(slackToLib.id.get)
            ltss.foreach { lts => lts.space === LibrarySpace.fromOrganizationId(org.id.get) }
            stls.foreach { stl => stl.space === LibrarySpace.fromOrganizationId(org.id.get) }
          }
          1 === 1
        }
      }

      "delete integrations" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, libToSlack, slackToLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(user).saved
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (user, lib, lts, stl)
          }
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].all.map(_.id.get) === Seq(libToSlack.id.get)
            inject[SlackChannelToLibraryRepo].all.map(_.id.get) === Seq(slackToLib.id.get)
          }
          val delRequest = SlackIntegrationDeleteRequest(user.id.get, libToSlack = Set(libToSlack.id.get), slackToLib = Set(slackToLib.id.get))
          slackCommander.deleteIntegrations(delRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].all.filter(_.isActive) === Seq.empty
            inject[SlackChannelToLibraryRepo].all.filter(_.isActive) === Seq.empty
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
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(owner).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (owner, member, org, lib, lts)
          }
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].all.map(_.id.get) === Seq(libToSlack.id.get)
          }

          // At first, if Member tries to modify they fail because they don't have access (the integration is in a personal space)
          val delRequest = SlackIntegrationDeleteRequest(member.id.get, libToSlack = Set(libToSlack.id.get), slackToLib = Set.empty)
          slackCommander.deleteIntegrations(delRequest) must beFailedTry

          // If the Owner moves it into an org space, though
          val moveRequest = SlackIntegrationModifyRequest(
            owner.id.get,
            libToSlack = Map(libToSlack.id.get -> SlackIntegrationModification(space = Some(LibrarySpace.fromOrganizationId(org.id.get)))),
            slackToLib = Map.empty
          )
          slackCommander.modifyIntegrations(moveRequest) must beSuccessfulTry

          // Then the Member can modify it
          slackCommander.deleteIntegrations(delRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            inject[LibraryToSlackChannelRepo].all.filter(_.isActive) === Seq.empty
          }
          1 === 1
        }
      }
    }
  }
}

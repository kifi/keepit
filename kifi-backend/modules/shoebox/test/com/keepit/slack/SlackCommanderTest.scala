package com.keepit.slack

import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
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
    "serve up a user's library integrations" in {
      "group integrations by channel" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib1, lib2) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val Seq(lib1, lib2) = LibraryFactory.libraries(2).saved
            val slackTeam = SlackTeamFactory.team()
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib2).withChannel("#a").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib2).withChannel("#b").saved

            (user, lib1, lib2)
          }
          val slackInfo = db.readOnlyMaster { implicit s =>
            slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib1.id.get, lib2.id.get))
          }
          slackInfo(lib1.id.get).map(_.integrations.length) must beSome(1)
          slackInfo(lib2.id.get).map(_.integrations.length) must beSome(2)
        }
      }
      "handle really dumb edge-cases correctly" in {
        withDb(modules: _*) { implicit injector =>
          // For instance, suppose you are a member of two different slack teams with the same name: "kifi" and "kifi"
          // in both teams there is an #eng channel
          // you subscribe a library to both of these channels
          val (user, lib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam1 = SlackTeamFactory.team().copy(teamName = SlackTeamName("kifi"))
            val slackTeam2 = SlackTeamFactory.team().copy(teamName = SlackTeamName("kifi"))

            val stm1 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam1).saved
            val stm2 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam2).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm1).withLibrary(lib).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm2).withLibrary(lib).withChannel("#eng").saved

            (user, lib)
          }
          val slackInfo = db.readOnlyMaster { implicit s =>
            slackCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib.id.get))
          }
          slackInfo(lib.id.get).map(_.integrations.length) must beSome(2)
        }
      }
    }
    "modify/delete existing integrations" in {
      "actually modify/delete them" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib, libToSlack, slackToLib) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (user, lib, lts, stl)
          }
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            val stls = inject[SlackChannelToLibraryRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            stls.map(_.id.get) === Set(slackToLib.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.On }
            stls.foreach { stl => stl.status === SlackIntegrationStatus.Off }
          }
          val modRequest = SlackIntegrationModifyRequest(user.id.get, libToSlack = Map(libToSlack.id.get -> SlackIntegrationStatus.Off), slackToLib = Map(slackToLib.id.get -> SlackIntegrationStatus.On))
          slackCommander.modifyIntegrations(modRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            val stls = inject[SlackChannelToLibraryRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            stls.map(_.id.get) === Set(slackToLib.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.Off }
            stls.foreach { stl => stl.status === SlackIntegrationStatus.On }
          }
          val delRequest = SlackIntegrationDeleteRequest(user.id.get, libToSlack = Set.empty, slackToLib = Set(slackToLib.id.get))
          slackCommander.deleteIntegrations(delRequest) must beSuccessfulTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            val stls = inject[SlackChannelToLibraryRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            stls.map(_.id.get) === Set.empty
          }
          1 === 1
        }
      }
      "don't let randos mess with my stuff" in {
        withDb(modules: _*) { implicit injector =>
          val (user, rando, lib, libToSlack) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val rando = UserFactory.user().saved
            val lib = LibraryFactory.library().saved
            val slackTeam = SlackTeamFactory.team()

            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved
            val lts = LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib).withChannel("#eng").saved

            (user, rando, lib, lts)
          }
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.On }
          }
          val modRequest = SlackIntegrationModifyRequest(rando.id.get, libToSlack = Map(libToSlack.id.get -> SlackIntegrationStatus.Off), slackToLib = Map.empty)
          slackCommander.modifyIntegrations(modRequest) must beFailedTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.On }
          }
          val delRequest = SlackIntegrationDeleteRequest(rando.id.get, libToSlack = Set(libToSlack.id.get), slackToLib = Set.empty)
          slackCommander.deleteIntegrations(delRequest) must beFailedTry
          db.readOnlyMaster { implicit s =>
            val ltss = inject[LibraryToSlackChannelRepo].getActiveByOwnerAndLibrary(user.id.get, lib.id.get)
            ltss.map(_.id.get) === Set(libToSlack.id.get)
            ltss.foreach { lts => lts.status === SlackIntegrationStatus.On }
          }
          1 === 1
        }
      }
    }
  }
}

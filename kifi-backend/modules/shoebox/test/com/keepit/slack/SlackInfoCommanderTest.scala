package com.keepit.slack

import com.google.inject.Injector
import com.keepit.common.actor.TestKitSupport
import com.keepit.common.concurrent.FakeExecutionContextModule
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryToSlackChannelFactoryHelper._
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.SpecificationLike
import play.api.libs.json.Json

class SlackInfoCommanderTest extends TestKitSupport with SpecificationLike with ShoeboxTestInjector {
  implicit val context = HeimdalContext.empty
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  val modules = Seq(
    FakeExecutionContextModule(),
    FakeSocialGraphModule()
  )

  "SlackInfoCommander" should {
    "serve up a user's library integrations" in {
      "group integrations by channel" in {
        withDb(modules: _*) { implicit injector =>
          val (user, lib1, lib2) = db.readWrite { implicit session =>
            val user = UserFactory.user().saved
            val Seq(lib1, lib2) = LibraryFactory.libraries(2).map(_.withOwner(user)).saved
            val slackTeam = SlackTeamFactory.team().saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib1).withChannel("#eng").saved

            LibraryToSlackChannelFactory.lts().withMembership(stm).withLibrary(lib2).withChannel("#a").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib2).withChannel("#b").saved

            (user, lib1, lib2)
          }
          val slackInfo = slackInfoCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib1.id.get, lib2.id.get))

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
            val slackTeam1 = SlackTeamFactory.team().withName("kifi").saved
            val slackTeam2 = SlackTeamFactory.team().withName("kifi").saved

            val stm1 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam1).saved
            val stm2 = SlackTeamMembershipFactory.membership().withUser(user).withTeam(slackTeam2).saved

            LibraryToSlackChannelFactory.lts().withMembership(stm1).withLibrary(lib).withChannel("#eng").saved
            SlackChannelToLibraryFactory.stl().withMembership(stm2).withLibrary(lib).withChannel("#eng").saved

            (user, lib)
          }
          val slackInfo = slackInfoCommander.getSlackIntegrationsForLibraries(user.id.get, Set(lib.id.get))
          // Uncomment to visually inspect the slack info
          // println(Json.prettyPrint(Json.toJson(slackInfo(lib.id.get))))
          slackInfo.get(lib.id.get).map(_.integrations.length) must beSome(2)
        }
      }
    }
    "serve up org integrations" in {
      "pick out the correct libraries" in {
        withDb(modules: _*) { implicit injector =>
          val (owner, member, org, libs) = db.readWrite { implicit session =>
            val owner = UserFactory.user().saved
            val member = UserFactory.user().saved

            val slackTeam = SlackTeamFactory.team().withName("slack").saved
            val stmOwner = SlackTeamMembershipFactory.membership().withUser(owner).withTeam(slackTeam).saved
            val stmMember = SlackTeamMembershipFactory.membership().withUser(member).withTeam(slackTeam).saved

            val personalSpace = LibrarySpace.fromUserId(member.id.get)
            LibraryFactory.libraries(5).map(_.withOwner(member).withVisibility(LibraryVisibility.SECRET)).saved.foreach { lib =>
              LibraryToSlackChannelFactory.lts().withMembership(stmMember).withLibrary(lib).withSpace(personalSpace).withChannel("#eng").saved
              SlackChannelToLibraryFactory.stl().withMembership(stmMember).withLibrary(lib).withSpace(personalSpace).withChannel("#eng").saved
            }

            val org = OrganizationFactory.organization().withOwner(owner).withMembers(Seq(member)).saved
            val orgSpace = LibrarySpace.fromOrganizationId(org.id.get)

            val personalLibs = List(
              LibraryFactory.library().withOwner(owner).withVisibility(LibraryVisibility.PUBLISHED).saved,
              LibraryFactory.library().withOwner(owner).withVisibility(LibraryVisibility.SECRET).saved
            )
            val orgLibs = List(
              LibraryFactory.library().withOwner(owner).withOrganization(org).saved,
              LibraryFactory.library().withOwner(member).withOrganization(org).saved
            )
            for (lib <- personalLibs ++ orgLibs) yield {
              LibraryToSlackChannelFactory.lts().withMembership(stmOwner).withLibrary(lib).withSpace(orgSpace).withChannel("#eng").saved
              SlackChannelToLibraryFactory.stl().withMembership(stmOwner).withLibrary(lib).withSpace(orgSpace).withChannel("#eng").saved
            }
            (owner, member, org, orgLibs ++ personalLibs)
          }

          val slackInfo = db.readOnlyMaster { implicit s =>
            slackInfoCommander.getOrganizationSlackInfo(org.id.get, member.id.get)
          }
          // Uncomment to visually inspect the slack info
          // println(Json.prettyPrint(Json.toJson(slackInfo)))

          val expected = libs.filter(_.visibility != LibraryVisibility.SECRET).map(_.id.get).toSet
          val actual = slackInfo.libraries.map(_._1.id).map(pubId => Library.decodePublicId(pubId).get).toSet
          actual === expected
        }
      }
    }
  }
}

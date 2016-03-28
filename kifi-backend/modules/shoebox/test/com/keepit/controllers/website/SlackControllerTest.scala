package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.model.SlackChannelToLibraryFactoryHelper._
import com.keepit.model.SlackTeamFactoryHelper._
import com.keepit.model.SlackChannelFactoryHelper._
import com.keepit.model.LibraryInviteFactoryHelper._
import com.keepit.model.SlackTeamMembershipFactoryHelper._
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.LibraryFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.slack.{ SlackAuthenticatedActionHelper, TurnChannelIngestion }
import com.keepit.slack.models._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json._
import play.api.mvc.Call
import play.api.test.FakeRequest
import play.api.test.Helpers._

class SlackControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)
  implicit def publicIdConfig(implicit injector: Injector) = inject[PublicIdConfiguration]
  private def controller(implicit injector: Injector) = inject[SlackController]
  private def route = com.keepit.controllers.website.routes.SlackController

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "SlackController" should {
    "let users modify integrations" in {
      "let them turn on ingestions without write permission if they can auto-join via invite" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, lib, stl, stm) = db.readWrite { implicit s =>
            val user = UserFactory.user().saved
            val lib = LibraryFactory.library().withOwner(UserFactory.user().saved).saved

            val slackTeam = SlackTeamFactory.team().saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withUsername("ryan-slack").withTeam(slackTeam).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel(channel).saved

            (user, lib, stl, stm)
          }

          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, user.id.get) must beNone
            inject[SlackChannelToLibraryRepo].get(stl.id.get).status === SlackIntegrationStatus.Off
          }

          val libPubId = Library.publicId(lib.id.get)
          inject[FakeUserActionsHelper].setUser(user)

          // The user does not have write access, they can't turn the integration on
          val request = route.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)
          val response1 = controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)
          status(response1) === FORBIDDEN

          // Give them an invite, though...
          db.readWrite { implicit s =>
            LibraryInviteFactory.invite().fromLibraryOwner(lib).toUser(user).saved
          }

          // Without the correct Scopes
          val redirectPlease = controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)
          status(redirectPlease) === OK
          (contentAsJson(redirectPlease) \ "success").asOpt[Boolean] should beNone
          (contentAsJson(redirectPlease) \ "redirect").asOpt[JsString] should beSome

          // With the correct Scopes
          db.readWrite { implicit s =>
            val requiredScopes = TurnChannelIngestion(integrationId = stl.id.get.id, turnOn = true).requiredScopes
            inject[SlackTeamMembershipRepo].save(stm.copy(tokenWithScopes = Some(SlackTokenWithScopes(SlackUserAccessToken("fake"), requiredScopes))))
          }

          val actionPerformed = controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)
          status(actionPerformed) === OK
          (contentAsJson(actionPerformed) \ "success").asOpt[Boolean] === Some(true)

          // They autojoined
          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, user.id.get) must beSome
            inject[SlackChannelToLibraryRepo].get(stl.id.get).status === SlackIntegrationStatus.On
          }
        }
      }
      "let them turn on ingestions without write permission if they can auto-join via open collaboration" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (user, lib, org, stl, stm) = db.readWrite { implicit s =>
            val user = UserFactory.user().saved

            val owner = UserFactory.user().saved
            val org = OrganizationFactory.organization().withOwner(owner).saved
            val lib = LibraryFactory.library().withOwner(owner).withOrganization(org).saved

            val slackTeam = SlackTeamFactory.team().saved
            val channel = SlackChannelFactory.channel().withTeam(slackTeam).withName("#eng").saved
            val stm = SlackTeamMembershipFactory.membership().withUser(user).withUsername("ryan-slack").withTeam(slackTeam).saved
            val stl = SlackChannelToLibraryFactory.stl().withMembership(stm).withLibrary(lib).withChannel(channel).saved

            (user, lib, org, stl, stm)
          }

          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, user.id.get) must beNone
            inject[SlackChannelToLibraryRepo].get(stl.id.get).status === SlackIntegrationStatus.Off
          }

          val libPubId = Library.publicId(lib.id.get)
          inject[FakeUserActionsHelper].setUser(user)

          val request = route.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)

          // The user does not have write access, they can't turn the integration on
          status(controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)) === FORBIDDEN

          // Being an org member doesn't help if the library doesn't have open collaboration
          orgMembershipCommander.addMembership(OrganizationMembershipAddRequest(org.id.get, org.ownerId, user.id.get)) must beRight
          status(controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)) === FORBIDDEN

          // Even if you have open collaboration, if it isn't for write access it won't help
          db.readWrite { implicit s =>
            libraryRepo.save(lib.withOpenCollab(Some(LibraryAccess.READ_ONLY)))
          }
          status(controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)) === FORBIDDEN

          // The only success case is open collab for write access
          db.readWrite { implicit s =>
            libraryRepo.save(lib.withOpenCollab(Some(LibraryAccess.READ_WRITE)))
          }

          // Without the correct Scopes
          val redirectPlease = controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)
          status(redirectPlease) === OK
          (contentAsJson(redirectPlease) \ "success").asOpt[Boolean] should beNone
          (contentAsJson(redirectPlease) \ "redirect").asOpt[JsString] should beSome

          // With the correct Scopes
          db.readWrite { implicit s =>
            val requiredScopes = TurnChannelIngestion(integrationId = stl.id.get.id, turnOn = true).requiredScopes
            inject[SlackTeamMembershipRepo].save(stm.copy(tokenWithScopes = Some(SlackTokenWithScopes(SlackUserAccessToken("fake"), requiredScopes))))
          }

          val actionPerformed = controller.ingestChannel(libPubId, SlackChannelToLibrary.publicId(stl.id.get).id, turnOn = true)(request)
          status(actionPerformed) === OK
          (contentAsJson(actionPerformed) \ "success").asOpt[Boolean] should beSome(true)

          // They autojoined
          db.readOnlyMaster { implicit s =>
            libraryMembershipRepo.getWithLibraryIdAndUserId(lib.id.get, user.id.get) must beSome
            inject[SlackChannelToLibraryRepo].get(stl.id.get).status === SlackIntegrationStatus.On
          }
        }
      }
    }
  }
}

package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.Future

class MobileOrganizationInviteControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "MobileOrganizationInviteController" should {
    "when acceptInvitation is called:" in {
      "succeed on invitation from owner of org" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val (invitee, invite) = db.readWrite { implicit session =>
            val invitee = UserFactory.user().withName("New", "Guy").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(owner.id.get, OrganizationRole.OWNER))

            val invite = inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = owner.id.get, userId = invitee.id, role = OrganizationRole.MEMBER))
            (invitee, invite)
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId, invite.authToken)
          val result = controller.acceptInvitation(publicOrgId, invite.authToken)(request)

          status(result) must equalTo(NO_CONTENT)
        }
      }

      "succeed on member with invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val (invitee, invite) = db.readWrite { implicit session =>
            val invitee = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))

            val invite = inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = invitee.id, role = OrganizationRole.MEMBER))
            (invitee, invite)
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId, invite.authToken)
          val result = controller.acceptInvitation(publicOrgId, invite.authToken)(request)
          status(result) must equalTo(NO_CONTENT)
        }
      }

      "fail from member without invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val (invitee, invite) = db.readWrite { implicit session =>
            val invitee = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER))

            val invite = inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = invitee.id, role = OrganizationRole.MEMBER))
            (invitee, invite)
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId, invite.authToken)
          val result = controller.acceptInvitation(publicOrgId, invite.authToken)(request)
          result === OrganizationFail.NO_VALID_INVITATIONS
        }
      }

      "fail when trying to elevate role above inviters role" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val (invitee, invite) = db.readWrite { implicit session =>
            val invitee = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))

            val invite = inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = invitee.id, role = OrganizationRole.OWNER))
            (invitee, invite)
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId, invite.authToken)
          val result = controller.acceptInvitation(publicOrgId, invite.authToken)(request)
          result === OrganizationFail.NO_VALID_INVITATIONS
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val invitee = db.readWrite { implicit session =>
            UserFactory.user().withName("New", "Guy").saved
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(PublicId[Organization]("12345"), "authToken")
          val result = controller.acceptInvitation(PublicId[Organization]("12345"), "authToken")(request)

          result === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }

    "when declineInvitation is called:" in {
      "succeed given a valid public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val inviteRepo = inject[OrganizationInviteRepo]
          val (invitee, org) = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))

            inviteRepo.save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = user.id, role = OrganizationRole.MEMBER))
            (user, org)
          }
          val orgId = org.id.get
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.declineInvitation(publicOrgId)
          val result = controller.declineInvitation(publicOrgId)(request)
          status(result) must equalTo(NO_CONTENT)

          db.readOnlyMaster { implicit session =>
            inviteRepo.getByOrgAndUserId(orgId, invitee.id.get).forall(_.decision == Some(InvitationDecision.DECLINED)) === true
          }
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val invitee = db.readWrite { implicit session =>
            UserFactory.user().withName("New", "Guy").saved
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.declineInvitation(PublicId[Organization]("12345"))
          val result = controller.declineInvitation(PublicId[Organization]("12345"))(request)

          result === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }

    "when createAnonymousInviteToOrganization is called:" in {
      "succeed for member with invite permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (inviter, org) = db.readWrite { implicit session =>
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))
            (inviter, org)
          }

          inject[FakeUserActionsHelper].setUser(inviter)
          val request = route.createAnonymousInviteToOrganization(Organization.publicId(org.id.get)).withBody(Json.obj("role" -> OrganizationRole.MEMBER))
          val result = controller.createAnonymousInviteToOrganization(Organization.publicId(org.id.get))(request)

          status(result) === OK

          val allInvitations = db.readOnlyMaster { implicit s =>
            inject[OrganizationInviteRepo].getAllByOrganization(org.id.get)
          }
          allInvitations.length === 1
          val onlyInvitation = allInvitations(0)
          val token = onlyInvitation.authToken

          val link = (Json.parse(contentAsString(result)) \ "link").as[String]
          link must contain(s"authToken=$token")
          link must contain(Organization.publicId(org.id.get).id)
          link must contain("http://dev.ezkeep.com:9000/m/1/")
        }
      }

      "fail on member trying to elevate permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (inviter, org) = db.readWrite { implicit session =>
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))
            (inviter, org)
          }

          inject[FakeUserActionsHelper].setUser(inviter)
          val request = route.createAnonymousInviteToOrganization(Organization.publicId(org.id.get)).withBody(Json.obj("role" -> OrganizationRole.OWNER))
          val result = controller.createAnonymousInviteToOrganization(Organization.publicId(org.id.get))(request)

          result === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }

      "fail for member without invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val (inviter, org) = db.readWrite { implicit session =>
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER))
            (inviter, org)
          }

          inject[FakeUserActionsHelper].setUser(inviter)
          val request = route.createAnonymousInviteToOrganization(Organization.publicId(org.id.get)).withBody(Json.obj("role" -> OrganizationRole.MEMBER))
          val result = controller.createAnonymousInviteToOrganization(Organization.publicId(org.id.get))(request)

          result === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          implicit val config = inject[PublicIdConfiguration]
          val inviter = db.readWrite { implicit session =>
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER))
            inviter
          }

          inject[FakeUserActionsHelper].setUser(inviter)
          val request = route.createAnonymousInviteToOrganization(PublicId[Organization]("12345")).withBody(Json.obj("role" -> OrganizationRole.MEMBER))
          val result = controller.createAnonymousInviteToOrganization(PublicId[Organization]("12345"))(request)

          result === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }
  }

  private def controller(implicit injector: Injector) = inject[MobileOrganizationInviteController]
  private def route = com.keepit.controllers.mobile.routes.MobileOrganizationInviteController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      contentType(result) must beSome("application/json")
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
    }
  }
}

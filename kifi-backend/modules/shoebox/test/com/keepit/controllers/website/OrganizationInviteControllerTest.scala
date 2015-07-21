package com.keepit.controllers.website

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.UserFactoryHelper._
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.{ JsString, JsValue, Json }
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }

class OrganizationInviteControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  "OrganizationInviteController" should {
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

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
          val request = route.declineInvitation(publicOrgId)
          val result = controller.declineInvitation(publicOrgId)(request)
          status(result) must equalTo(NO_CONTENT)

          val inactiveInvites = db.readOnlyMaster { implicit session =>
            inviteRepo.getByOrgAndUserId(orgId, invitee.id.get, state = OrganizationInviteStates.INACTIVE)
          }
          inactiveInvites.length == 1
          inactiveInvites.forall(_.decision == InvitationDecision.DECLINED) === true
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val invitee = db.readWrite { implicit session =>
            UserFactory.user().withName("New", "Guy").saved
          }

          inject[FakeUserActionsHelper].setUser(invitee, Set(UserExperimentType.ORGANIZATION))
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
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.VIEW_ORGANIZATION, OrganizationPermission.INVITE_MEMBERS)))
            (inviter, org)
          }

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
          val request = route.createAnonymousInviteToOrganization(Organization.publicId(org.id.get)).withBody(JsString(""))
          val result = controller.createAnonymousInviteToOrganization(Organization.publicId(org.id.get))(request)

          status(result) === OK

          val allInvitations = db.readOnlyMaster { implicit s =>
            inject[OrganizationInviteRepo].getAllByOrganization(org.id.get)
          }
          allInvitations.size === 1
          val onlyInvitation = allInvitations.head
          val token = onlyInvitation.authToken

          val link = (Json.parse(contentAsString(result)) \ "link").as[String]
          link must contain(s"authToken=$token")
          link must contain(Organization.publicId(org.id.get).id)
          link must contain("http://dev.ezkeep.com:9000/site/") // TODO(ryan): why is this a requirement?
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

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
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

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
          val request = route.createAnonymousInviteToOrganization(PublicId[Organization]("12345")).withBody(Json.obj("role" -> OrganizationRole.MEMBER))
          val result = controller.createAnonymousInviteToOrganization(PublicId[Organization]("12345"))(request)

          result === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }

    "when inviteUsers is called:" in {
      def setupInviters()(implicit injector: Injector) = {
        db.readWrite { implicit session =>
          val owner = UserFactory.user().withName("The", "Unknown").saved
          val inviter = UserFactory.user().withEmailAddress("inviter@kifi.com").saved
          val cannot_invite = UserFactory.user().saved
          val not_a_member = UserFactory.user().saved
          val org = OrganizationFactory.organization().withName("Void").withOwner(owner).withMembers(Seq(inviter, cannot_invite)).saved

          val inviterMembership = inject[OrganizationMembershipRepo].getByOrgIdAndUserId(org.id.get, inviter.id.get).get
          inject[OrganizationMembershipRepo].save(inviterMembership.withPermissions(Set(OrganizationPermission.INVITE_MEMBERS, OrganizationPermission.VIEW_ORGANIZATION)))

          (org, owner, inviter, cannot_invite, not_a_member)
        }
      }

      "succeed for member that has invite permissions" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, inviter, cannot_invite, not_a_member) = setupInviters()
          implicit val config = inject[PublicIdConfiguration]
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
          val jsonInput = Json.parse(
            s"""{ "invites": [
               |{ "id":  "${not_a_member.externalId.id}"}
               |]}""".stripMargin)
          val request = route.inviteUsers(publicId).withBody(jsonInput)
          val response = controller.inviteUsers(publicId)(request)

          val content = contentAsString(response)
          status(response) === OK
          content must contain(not_a_member.externalId.id)
        }
      }

      "fail on badly formed inputs" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, inviter, cannot_invite, nonMember) = setupInviters()
          implicit val config = inject[PublicIdConfiguration]
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))

          val badInputs = Seq(
            Json.parse(s"""{ "invites": [{ "id":  "${nonMember.externalId.id}", "email": "ryan@kifi.com"}]}"""), // Both email and id
            Json.parse(s"""{ "invites": [{ "role": "member" }] }""") // neither email nor id
          )

          for (badInput <- badInputs) {
            val request = route.inviteUsers(publicId).withBody(badInput)
            val result = controller.inviteUsers(publicId)(request)
            status(result) === BAD_REQUEST
          }
          1 === 1
        }
      }

      "fail for member without invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, inviter, cannot_invite, not_a_member) = setupInviters()
          implicit val config = inject[PublicIdConfiguration]
          val publicId = Organization.publicId(org.id.get)

          inject[FakeUserActionsHelper].setUser(cannot_invite, Set(UserExperimentType.ORGANIZATION))
          val jsonInput = Json.parse(
            s"""{ "invites": [
               |{ "id":  "${not_a_member.externalId.id}"}
                                                         |]}""".stripMargin)
          val request = route.inviteUsers(publicId).withBody(jsonInput)
          val response = controller.inviteUsers(publicId)(request)

          response === OrganizationFail.INSUFFICIENT_PERMISSIONS
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, inviter, cannot_invite, not_a_member) = setupInviters()
          implicit val config = inject[PublicIdConfiguration]
          // guaranteed to be random, because it was generated with a random number generator.
          val publicId = PublicId[Organization]("2267")

          inject[FakeUserActionsHelper].setUser(inviter, Set(UserExperimentType.ORGANIZATION))
          val jsonInput = Json.parse(
            s"""{ "invites": [
               |{ "id":  "${not_a_member.externalId.id}"}
                                                         |]}""".stripMargin)
          val request = route.inviteUsers(publicId).withBody(jsonInput)
          val response = controller.inviteUsers(publicId)(request)

          response === OrganizationFail.INVALID_PUBLIC_ID
        }
      }
    }

    "when cancelling invites" in {
      "be able to cancel an email invite" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val email = EmailAddress("ryan@kifi.com")
          val (org, owner) = db.readWrite { implicit session =>
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = OrganizationFactory.organization.withName("Kifi").withOwner(owner).withInvitedEmails(Seq(email)).saved
            (org, owner)
          }
          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          val body = Json.obj("cancel" -> Json.arr(Json.obj("email" -> email)))
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.cancelInvites(publicOrgId).withBody(body)
          val result = controller.cancelInvites(publicOrgId)(request)

          status(result) must equalTo(OK)
          contentAsString(result) must contain("ryan@kifi.com")
        }
      }
      "be able to cancel a user id invite" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization.withName("Kifi").withOwner(owner).withInvitedUsers(Seq(rando)).saved
            (org, owner, rando)
          }
          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          val body = Json.obj("cancel" -> Json.arr(Json.obj("id" -> rando.externalId)))
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.cancelInvites(publicOrgId).withBody(body)
          val result = controller.cancelInvites(publicOrgId)(request)

          status(result) must equalTo(OK)
          contentAsString(result) must contain(rando.externalId.id)
        }
      }
      "fail if the invite doesn't exist" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val (org, owner, rando) = db.readWrite { implicit session =>
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val rando = UserFactory.user().saved
            val org = OrganizationFactory.organization.withName("Kifi").withOwner(owner).withInvitedUsers(Seq(rando)).saved
            (org, owner, rando)
          }
          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          val email = EmailAddress("ryan@kifi.com") // no invite for this email
          val body = Json.obj("cancel" -> Json.arr(Json.obj("email" -> email)))
          inject[FakeUserActionsHelper].setUser(owner, Set(UserExperimentType.ORGANIZATION))
          val request = route.cancelInvites(publicOrgId).withBody(body)
          val result = controller.cancelInvites(publicOrgId)(request)

          status(result) must equalTo(FORBIDDEN) // TODO(ryan): fix the organization fails, this should give a BAD_REQUEST
        }
      }
    }
  }

  private def controller(implicit injector: Injector) = inject[OrganizationInviteController]
  private def route = com.keepit.controllers.website.routes.OrganizationInviteController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      contentType(result) must beSome("application/json")
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
    }
  }
}

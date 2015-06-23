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
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.{ Result, Call }
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
      "accept invitation from owner of org" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val invitee = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("New", "Guy").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(owner.id.get, OrganizationRole.OWNER))

            inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = owner.id.get, userId = user.id, role = OrganizationRole.MEMBER))
            user
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId)
          val result = controller.acceptInvitation(publicOrgId)(request)

          status(result) must equalTo(OK)
        }
      }

      "accept on member with invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val invitee = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))

            inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = user.id, role = OrganizationRole.MEMBER))
            user
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId)
          val result = controller.acceptInvitation(publicOrgId)(request)
          status(result) must equalTo(OK)
        }
      }

      "fail from member without invite rights" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val invitee = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER))

            inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = user.id, role = OrganizationRole.MEMBER))
            user
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId)
          val result = controller.acceptInvitation(publicOrgId)(request)
          result === OrganizationFail.NO_VALID_INVITATIONS
        }
      }

      "fail when trying to elevate role above inviters role" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val orgId = Id[Organization](1)
          val publicOrgId = Organization.publicId(orgId)(inject[PublicIdConfiguration])
          val invitee = db.readWrite { implicit session =>
            val user = UserFactory.user().withName("New", "Guy").saved
            val inviter = UserFactory.user().withName("Mr", "Inviter").saved
            val owner = UserFactory.user().withName("Kifi", "Kifi").saved
            val org = inject[OrganizationRepo].save(Organization(name = "Kifi", ownerId = owner.id.get, handle = None))
            inject[OrganizationMembershipRepo].save(org.newMembership(inviter.id.get, OrganizationRole.MEMBER).withPermissions(Set(OrganizationPermission.INVITE_MEMBERS)))

            inject[OrganizationInviteRepo].save(OrganizationInvite(organizationId = org.id.get, inviterId = inviter.id.get, userId = user.id, role = OrganizationRole.OWNER))
            user
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(publicOrgId)
          val result = controller.acceptInvitation(publicOrgId)(request)
          result === OrganizationFail.NO_VALID_INVITATIONS
        }
      }

      "fail on bad public id" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val invitee = db.readWrite { implicit session =>
            UserFactory.user().withName("New", "Guy").saved
          }

          inject[FakeUserActionsHelper].setUser(invitee)
          val request = route.acceptInvitation(PublicId[Organization]("12345"))
          val result = controller.acceptInvitation(PublicId[Organization]("12345"))(request)

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

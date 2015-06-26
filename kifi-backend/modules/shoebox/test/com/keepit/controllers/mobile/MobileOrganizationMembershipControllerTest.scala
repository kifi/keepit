package com.keepit.controllers.mobile

import com.google.inject.Injector
import com.keepit.abook.FakeABookServiceClientModule
import com.keepit.commanders.OrganizationMembershipCommander
import com.keepit.common.controller.FakeUserActionsHelper
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.social.FakeSocialGraphModule
import com.keepit.heimdal.FakeHeimdalServiceClientModule
import com.keepit.model.OrganizationFactoryHelper._
import com.keepit.model.UserFactoryHelper._
import com.keepit.model._
import com.keepit.test.ShoeboxTestInjector
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.mvc.{ Call, Result }
import play.api.test.FakeRequest
import play.api.test.Helpers._
import play.api.libs.json._

import scala.concurrent.Future

class MobileOrganizationMembershipControllerTest extends Specification with ShoeboxTestInjector {
  implicit def createFakeRequest(route: Call) = FakeRequest(route.method, route.url)

  val controllerTestModules = Seq(
    FakeHeimdalServiceClientModule(),
    FakeABookServiceClientModule(),
    FakeSocialGraphModule()
  )

  //final case class MaybeOrganizationMember(member: Either[BasicUser, BasicContact], role: Option[OrganizationRole], lastInvitedAt: Option[DateTime])
  "MobileOrganizationMembershipController" should {
    def setup(numMembers: Int = 0, numInvitedUsers: Int = 0, numInvitedEmails: Int = 0)(implicit injector: Injector) = {
      db.readWrite { implicit session =>
        val members = UserFactory.users(numMembers).saved
        val invitedUsers = UserFactory.users(numInvitedUsers).saved
        val invitedEmails = List.fill(numInvitedEmails)(EmailAddress("ryan@kifi.com"))
        val owner = UserFactory.user().withName("A", "Moneybags").saved

        val org = OrganizationFactory.organization()
          .withName("Moneybags, LLC")
          .withOwner(owner)
          .withMembers(members)
          .withInvitedUsers(invitedUsers)
          .withInvitedEmails(invitedEmails)
          .saved

        (org, owner, members, invitedUsers, invitedEmails)
      }
    }
    "display an organization's members" in {
      "list an organization's members" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val n = 20
          val (org, owner, members, _, _) = setup(numMembers = n - 1)

          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          inject[FakeUserActionsHelper].setUser(members.head)
          val request = route.getMembers(publicOrgId)
          val result = controller.getMembers(publicOrgId, offset = 0, limit = n)(request)
          status(result) === OK

          val json = Json.parse(contentAsString(result))
          val resultMembersList = (json \ "members").as[Seq[JsValue]]
          resultMembersList.length === n
          (json \ "members" \\ "id").length == n
          (json \ "members" \\ "id").map(v => v.as[ExternalId[User]]) == (owner :: members.toList).take(n).map(_.externalId)
        }
      }
      "list an organization's invitees, if the requester can invite members" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val n = 10
          val (org, owner, members, invitedUsers, _) = setup(numMembers = n - 1, numInvitedUsers = n)

          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          inject[OrganizationMembershipCommander].getPermissions(orgId = org.id.get, userIdOpt = Some(owner.id.get)).contains(OrganizationPermission.INVITE_MEMBERS) === true
          inject[OrganizationMembershipCommander].getPermissions(orgId = org.id.get, userIdOpt = Some(members.head.id.get)).contains(OrganizationPermission.INVITE_MEMBERS) === false

          inject[FakeUserActionsHelper].setUser(members.head)
          val memberRequest = route.getMembers(publicOrgId)
          val memberResult = controller.getMembers(publicOrgId, offset = n, limit = n)(memberRequest)
          status(memberResult) === OK

          {
            // Members (without invite permissions) cannot view invitees
            val json = Json.parse(contentAsString(memberResult))
            val resultInviteesList = (json \ "members").as[Seq[JsValue]]
            resultInviteesList.length === 0
          }

          inject[FakeUserActionsHelper].setUser(owner)
          val ownerRequest = route.getMembers(publicOrgId)
          val ownerResult = controller.getMembers(publicOrgId, offset = n, limit = n)(ownerRequest)
          status(ownerResult) === OK

          {
            val json = Json.parse(contentAsString(ownerResult))
            println(json)
            val resultInviteesList = (json \ "members").as[Seq[JsValue]]
            resultInviteesList.length === n
            (json \ "members" \\ "id").length === n
            (json \ "members" \\ "id").map(v => v.as[ExternalId[User]]) === invitedUsers.take(n).map(_.externalId)
          }

        }
      }

      "list invitee emails, if the requester can invite members" in {
        withDb(controllerTestModules: _*) { implicit injector =>
          val n = 10
          val (org, owner, members, invitedUsers, invitedEmails) = setup(numMembers = n - 1, numInvitedUsers = n, numInvitedEmails = n)

          val publicOrgId = Organization.publicId(org.id.get)(inject[PublicIdConfiguration])

          inject[OrganizationMembershipCommander].getPermissions(orgId = org.id.get, userIdOpt = Some(owner.id.get)).contains(OrganizationPermission.INVITE_MEMBERS) === true

          inject[FakeUserActionsHelper].setUser(owner)
          val ownerRequest = route.getMembers(publicOrgId)
          val ownerResult = controller.getMembers(publicOrgId, offset = 2 * n, limit = n)(ownerRequest)
          status(ownerResult) === OK

          val json = Json.parse(contentAsString(ownerResult))
          println(json)
          val resultInviteesList = (json \ "members").as[Seq[JsValue]]
          resultInviteesList.length === n
          (json \ "members" \\ "id").length === 0 // no user ids, only emails
          (json \ "members" \\ "email").length === n
          (json \ "members" \\ "email").map(v => v.as[EmailAddress]) === invitedEmails.take(n)
        }
      }
    }
  }

  private def controller(implicit injector: Injector) = inject[MobileOrganizationMembershipController]
  private def route = com.keepit.controllers.mobile.routes.MobileOrganizationMembershipController
  implicit class ResultWrapper(result: Future[Result]) {
    def ===(failure: OrganizationFail) = {
      status(result) must equalTo(failure.status)
      contentType(result) must beSome("application/json")
      (Json.parse(contentAsString(result)) \ "error").as[String] === failure.message
    }
  }
}

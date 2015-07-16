package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.mail.EmailAddress
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@Singleton
class MobileOrganizationInviteController @Inject() (
    userCommander: UserCommander,
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    orgInviteCommander: OrganizationInviteCommander,
    fortyTwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def inviteUsers(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS).async(parse.tolerantJson) { request =>
    val invites = (request.body \ "invites").as[JsArray].value
    val messageOpt = (request.body \ "message").asOpt[String].filter(_.nonEmpty)

    val userInvites = invites.filter(inv => (inv \ "type").as[String] == "user")
    val emailInvites = invites.filter(inv => (inv \ "type").as[String] == "email")

    val userExternalIds = userInvites.map(inv => (inv \ "id").as[ExternalId[User]])
    val userMap = userCommander.getByExternalIds(userExternalIds)
    val userInfo = userInvites.map { userInvite =>
      val externalId = (userInvite \ "id").as[ExternalId[User]]
      val userId = userMap(externalId).id.get
      val role = (userInvite \ "role").as[OrganizationRole]
      OrganizationMemberInvitation(Left(userId), role)
    }

    val emailInfo = emailInvites.map { emailInvite =>
      val email = (emailInvite \ "email").as[EmailAddress]
      val role = (emailInvite \ "role").as[OrganizationRole]
      OrganizationMemberInvitation(Right(email), role)
    }

    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    val inviteResult = orgInviteCommander.inviteToOrganization(request.orgId, request.request.userId, userInfo ++ emailInfo, messageOpt)
    inviteResult.map {
      case Right(inviteesWithAccess) =>
        val result = inviteesWithAccess.map {
          case (Left(user), role) => Json.obj("user" -> user.externalId, "role" -> role)
          case (Right(contact), role) => Json.obj("email" -> contact.email, "role" -> role)
        }
        Ok(Json.obj("result" -> "success", "invitees" -> JsArray(result)))
      case Left(organizationFail) => organizationFail.asErrorResponse
    }
  }

  def createAnonymousInviteToOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS)(parse.tolerantJson) { request =>
    val role = (request.body \ "role").as[OrganizationRole]
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    orgInviteCommander.createGenericInvite(request.orgId, request.request.userId, role) match {
      case Right(invite) =>
        Ok(Json.obj("link" -> (fortyTwoConfig.applicationBaseUrl + routes.MobileOrganizationInviteController.acceptInvitation(Organization.publicId(invite.organizationId), invite.authToken).url)))
      case Left(fail) => fail.asErrorResponse
    }
  }

  def acceptInvitation(pubId: PublicId[Organization], authToken: String) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        orgInviteCommander.acceptInvitation(orgId, request.userId, authToken) match {
          case Right(organizationMembership) => NoContent
          case Left(organizationFail) => organizationFail.asErrorResponse
        }
      case Failure(_) => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
    }
  }

  def declineInvitation(pubId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        organizationInviteCommander.declineInvitation(orgId, request.userId)
        NoContent
      case _ => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
    }
  }
}

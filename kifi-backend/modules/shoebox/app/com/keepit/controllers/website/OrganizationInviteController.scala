package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@Singleton
class OrganizationInviteController @Inject() (
    userCommander: UserCommander,
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    orgInviteCommander: OrganizationInviteCommander,
    fortyTwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def inviteUsers(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS).async(parse.tolerantJson) { request =>
    val messageOpt = (request.body \ "message").asOpt[String].filter(_.nonEmpty)

    (request.body \ "invites").validate[Seq[ExternalOrganizationMemberInvitation]] match {
      case JsError(errs) => Future.successful(BadRequest(Json.obj("error" -> "could_not_parse_invites")))
      case JsSuccess(externalInvites, _) =>
        val userExternalIds = externalInvites.map(_.invited).collect { case Left(userId) => userId }
        val externalToInternalIdMap = userCommander.getByExternalIds(userExternalIds).mapValues(_.id.get)
        val invites = externalInvites.map {
          case ExternalOrganizationMemberInvitation(Left(extId), role) =>
            OrganizationMemberInvitation(Left(externalToInternalIdMap(extId)), role)
          case ExternalOrganizationMemberInvitation(Right(email), role) =>
            OrganizationMemberInvitation(Right(email), role)
        }

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
        val inviteResult = orgInviteCommander.inviteToOrganization(request.orgId, request.request.userId, invites, message = messageOpt)
        inviteResult.map {
          case Left(organizationFail) => organizationFail.asErrorResponse
          case Right(inviteesWithAccess) =>
            val result = inviteesWithAccess.map {
              case (Left(user), role) => Json.obj("user" -> user.externalId, "role" -> role)
              case (Right(contact), role) => Json.obj("email" -> contact.email, "role" -> role)
            }
            Ok(Json.obj("result" -> "success", "invitees" -> JsArray(result)))
        }
    }
  }

  def createAnonymousInviteToOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS)(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    orgInviteCommander.createGenericInvite(request.orgId, request.request.userId, OrganizationRole.MEMBER) match {
      case Right(invite) =>
        Ok(Json.obj("link" -> (fortyTwoConfig.applicationBaseUrl + routes.OrganizationInviteController.acceptInvitation(Organization.publicId(invite.organizationId), invite.authToken).url)))
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

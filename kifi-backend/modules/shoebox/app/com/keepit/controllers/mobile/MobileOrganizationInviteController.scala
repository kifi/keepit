package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.json.EitherFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.heimdal.{ HeimdalContext, HeimdalContextBuilderFactory }
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

@Singleton
class MobileOrganizationInviteController @Inject() (
    userCommander: UserCommander,
    orgInfoCommander: OrganizationInfoCommander,
    orgInviteCommander: OrganizationInviteCommander,
    fortyTwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def inviteUsers(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS).async(parse.tolerantJson) { request =>

    val messageOpt = (request.body \ "message").asOpt[String].filter(_.nonEmpty)
    implicit val reads = EitherFormat.keyedReads[ExternalId[User], EmailAddress]("id", "email")
    val invitesValidated = (request.body \ "invites").validate[Seq[Either[ExternalId[User], EmailAddress]]]

    invitesValidated match {
      case JsError(errs) => Future.successful(BadRequest(Json.obj("error" -> "could_not_parse_invites")))
      case JsSuccess(externalInvites, _) =>
        val userExternalIds = externalInvites.collect { case Left(userId) => userId }
        val externalToInternalIdMap = userCommander.getByExternalIds(userExternalIds).mapValues(_.id.get)
        val invitees: Set[Either[Id[User], EmailAddress]] = externalInvites.collect {
          case Left(extId) => Left(externalToInternalIdMap(extId))
          case Right(email) => Right(email)
        }.toSet

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Mobile).build
        val inviteeEmails = invitees.collect { case Right(email) => email }
        val inviteeUserIds = invitees.collect { case Left(userId) => userId }
        val orgInvite = OrganizationInviteSendRequest(request.orgId, request.request.userId, inviteeEmails, inviteeUserIds, messageOpt)
        val inviteResult = orgInviteCommander.inviteToOrganization(orgInvite)
        inviteResult.map {
          case Left(organizationFail) => organizationFail.asErrorResponse
          case Right(inviteesWithAccess) =>
            val result = inviteesWithAccess.map {
              case Left(user) => Json.obj("id" -> user.externalId)
              case Right(contact) => Json.obj("email" -> contact.email)
            }.toSeq
            Ok(Json.obj("result" -> "success", "invitees" -> JsArray(result)))
        }
    }
  }

  def createAnonymousInviteToOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS)(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Mobile).build
    orgInviteCommander.createGenericInvite(request.orgId, request.request.userId) match {
      case Right(invite) =>
        Ok(Json.obj("link" -> "https://nothing.com"))
      //        Ok(Json.obj("link" -> (fortyTwoConfig.applicationBaseUrl + routes.MobileOrganizationInviteController.acceptInvitation(Organization.publicId(invite.organizationId), Some(invite.authToken)).url)))
      case Left(fail) => fail.asErrorResponse
    }
  }

  def acceptInvitation(pubId: PublicId[Organization], authTokenOpt: Option[String]) = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Mobile).build
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        orgInviteCommander.acceptInvitation(orgId, request.userId, authTokenOpt) match {
          case Right(organizationMembership) => NoContent
          case Left(organizationFail) => organizationFail.asErrorResponse
        }
      case Failure(_) => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
    }
  }

  def declineInvitation(pubId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Success(orgId) =>
        orgInviteCommander.declineInvitation(orgId, request.userId)
        NoContent
      case _ => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
    }
  }

  def getPendingOrganizationsForUser = UserAction { request =>
    val userId = request.userId
    val pendingOrgs = orgInviteCommander.getInvitesByInviteeAndDecision(userId, InvitationDecision.PENDING).map(_.organizationId)
    val pendingOrgInfos = orgInfoCommander.getOrganizationInfos(pendingOrgs, viewerIdOpt = None).values.toSeq
    Ok(Json.toJson(pendingOrgInfos))
  }
}

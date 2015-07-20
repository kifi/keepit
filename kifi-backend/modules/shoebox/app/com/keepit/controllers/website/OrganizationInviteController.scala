package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.ExternalId
import com.keepit.common.json.EitherFormat
import com.keepit.common.mail.EmailAddress
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

    implicit val reads = EitherFormat.keyedReads[ExternalId[User], EmailAddress]("id", "email")
    val invitesValidated = (request.body \ "invites").validate[Seq[Either[ExternalId[User], EmailAddress]]]

    invitesValidated match {
      case JsError(errs) => Future.successful(BadRequest(Json.obj("error" -> "could_not_parse_invites")))
      case JsSuccess(externalInvites, _) =>
        val userExternalIds = externalInvites.collect { case Left(userId) => userId }
        val externalToInternalIdMap = userCommander.getByExternalIds(userExternalIds).mapValues(_.id.get)
        val invites = externalInvites.map {
          case Left(extId) => OrganizationMemberInvitation(Left(externalToInternalIdMap(extId)), OrganizationRole.MEMBER)
          case Right(email) => OrganizationMemberInvitation(Right(email), OrganizationRole.MEMBER)
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
  def cancelInvites(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.INVITE_MEMBERS)(parse.tolerantJson) { request =>
    implicit val reads = EitherFormat.keyedReads[ExternalId[User], EmailAddress]("id", "email")
    val invitesValidated = (request.body \ "cancel").validate[Seq[Either[ExternalId[User], EmailAddress]]]

    invitesValidated match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "could_not_parse_cancelations"))
      case JsSuccess(externalInvites, _) =>
        val emails = externalInvites.collect { case Right(email) => email }.toSet

        val userExternalIds = externalInvites.collect { case Left(extId) => extId }
        val externalToInternalIdMap = userCommander.getByExternalIds(userExternalIds).mapValues(_.id.get)
        val internalToExternalIdMap = externalToInternalIdMap.map(_.swap)
        val userIds = userExternalIds.map(externalToInternalIdMap.apply).toSet

        val cancelRequest = OrganizationInviteCancelRequest(orgId = request.orgId, requesterId = request.request.userId, targetEmails = emails, targetUserIds = userIds)
        val cancelResponse = orgInviteCommander.cancelOrganizationInvites(cancelRequest)
        cancelResponse match {
          case Left(organizationFail) => organizationFail.asErrorResponse
          case Right(cancelResult) =>
            val cancelledEmails = cancelResult.cancelledEmails
            val cancelledUserIds = cancelResult.cancelledUserIds.map(internalToExternalIdMap.apply)

            val cancelledEmailsJson = JsArray(cancelledEmails.toSeq.map { email => Json.obj("email" -> email) })
            val cancelledUserIdsJson = JsArray(cancelledUserIds.toSeq.map { userId => Json.obj("id" -> userId) })
            Ok(Json.obj("cancelled" -> (cancelledUserIdsJson ++ cancelledEmailsJson)))
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

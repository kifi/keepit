package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.json.EitherFormat
import com.keepit.common.mail.EmailAddress
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.inject.FortyTwoConfig
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import com.keepit.slack.models.{ SlackFail, SlackUsername }
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Try, Failure, Success }

@Singleton
class OrganizationInviteController @Inject() (
    userCommander: UserCommander,
    orgInviteCommander: OrganizationInviteCommander,
    organizationInviteCommander: OrganizationInviteCommander,
    fortyTwoConfig: FortyTwoConfig,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    airbrake: AirbrakeNotifier,
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

        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build

        val inviteeUserIds = invitees.collect { case Left(userId) => userId }
        val inviteeEmailAddresses = invitees.collect { case Right(emailAddress) => emailAddress }

        val sendInviteRequest = OrganizationInviteSendRequest(orgId = request.orgId, requesterId = request.request.userId, inviteeEmailAddresses, inviteeUserIds, messageOpt)

        val inviteResult = orgInviteCommander.inviteToOrganization(sendInviteRequest)
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
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
    orgInviteCommander.createGenericInvite(request.orgId, request.request.userId) match {
      case Right(invite) =>
        Ok(Json.obj("link" -> (fortyTwoConfig.applicationBaseUrl + "whatever")))//routes.OrganizationInviteController.acceptInvitation(Organization.publicId(invite.organizationId), Some(invite.authToken)).url)))
      case Left(fail) => fail.asErrorResponse
    }
  }

  def acceptInvitation(pubId: PublicId[Organization], authTokenOpt: Option[String]) = UserAction { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.Site).build
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
        organizationInviteCommander.declineInvitation(orgId, request.userId)
        NoContent
      case _ => OrganizationFail.INVALID_PUBLIC_ID.asErrorResponse
    }
  }

  def suggestMembers(pubId: PublicId[Organization], query: Option[String], limit: Int) = OrganizationUserAction(pubId).async { request =>
    if (limit > 30) { Future.successful(BadRequest(Json.obj("error" -> "invalid_limit"))) }
    organizationInviteCommander.suggestMembers(request.request.userId, request.orgId, query, limit, request.request).map { members => Ok(Json.obj("members" -> members)) }
  }

  def sendOrganizationInviteViaSlack(pubId: PublicId[Organization]) = MaybeUserAction.async(parse.tolerantJson) { request =>
    import com.keepit.common.core._
    Organization.decodePublicId(pubId) match {
      case Failure(_) => Future.failed(OrganizationFail.INVALID_PUBLIC_ID)
      case Success(orgId) =>
        (request.body \ "username").asOpt[String] match {
          case None => Future.failed(OrganizationFail.BAD_PARAMETERS)
          case Some(rawUsername) =>
            val username = SlackUsername(rawUsername.replaceAllLiterally("@", ""))
            orgInviteCommander.sendOrganizationInviteViaSlack(username, orgId, request.userIdOpt)
              .imap { _ => NoContent }
              .recover {
                case fail: OrganizationFail => fail.asErrorResponse
                case fail: SlackFail => fail.asResponse
              }
        }
    }
  }
}

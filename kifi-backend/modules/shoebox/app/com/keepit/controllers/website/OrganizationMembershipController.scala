package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._
import com.keepit.common.json.KeyFormat

import scala.util.{ Failure, Success }

@Singleton
class OrganizationMembershipController @Inject() (
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  // If userIdOpt is provided AND the user can invite members, return invited users as well as members
  def getMembers(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION, OrganizationPermission.VIEW_MEMBERS) { request =>
    if (limit > 30) {
      BadRequest(Json.obj("error" -> "invalid_limit"))
    } else Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val showInvitees = request.permissions.contains(OrganizationPermission.INVITE_MEMBERS)
        orgMembershipCommander.getMembersAndUniqueInvitees(orgId, request.request.userIdOpt, Offset(offset), Limit(limit), includeInvitees = showInvitees) match {
          case Left(fail) => fail.asErrorResponse
          case Right(membersAndMaybeInvitees) => Ok(Json.obj("members" -> membersAndMaybeInvitees))
        }
    }
  }

  def modifyMembers(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MODIFY_MEMBERS)(parse.tolerantJson) { request =>
    implicit val format = KeyFormat.key2Format[ExternalId[User], OrganizationRole]("userId", "newRole")
    val modifyParamsValidated = (request.body \ "members").validate[Seq[(ExternalId[User], OrganizationRole)]]

    modifyParamsValidated match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate modifyRequests from ${request.request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(modifyParams, _) =>
        val (externalIds, roles) = modifyParams.unzip

        val roleMap = (externalIds, roles).zipped.toMap
        val userIdMap = userCommander.getByExternalIds(externalIds).mapValues(_.id.get)
        val externalIdMap = userIdMap.map(_.swap)
        val modifyRequests = externalIds.map { extId =>
          OrganizationMembershipModifyRequest(request.orgId, request.request.userId, targetId = userIdMap(extId), newRole = roleMap(extId))
        }
        val modifyResponses = orgMembershipCommander.modifyMemberships(modifyRequests) collect { case (_, Right(success)) => success }
        val modifications = modifyResponses.map(r => externalIdMap(r.membership.userId) -> r.membership.role)
        Ok(Json.obj("modifications" -> modifications))
    }
  }

  def removeMembers(pubId: PublicId[Organization]) = OrganizationUserAction(pubId)(parse.tolerantJson) { request =>
    implicit val format = KeyFormat.key1Format[ExternalId[User]]("userId")
    val removeParamsValidated = (request.body \ "members").validate[Seq[ExternalId[User]]]

    removeParamsValidated match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate removeRequests from ${request.request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(removeParams, _) =>
        val externalIds = removeParams

        val userIdMap = userCommander.getByExternalIds(externalIds).mapValues(_.id.get)
        val externalIdMap = userIdMap.map(_.swap)
        val removeRequests = externalIds.map { extId =>
          OrganizationMembershipRemoveRequest(request.orgId, request.request.userId, targetId = userIdMap(extId))
        }
        val removeResponses = orgMembershipCommander.removeMemberships(removeRequests) collect { case (_, Right(success)) => success }
        val removals = removeResponses.map { r => externalIdMap(r.request.targetId) }

        Ok(Json.obj("removals" -> removals))
    }
  }

  def leaveOrganization(pubId: PublicId[Organization]) = UserAction { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_id"))
      case Success(orgId) =>
        val leaveRequest = OrganizationMembershipRemoveRequest(orgId, request.userId, request.userId)
        orgMembershipCommander.removeMembership(leaveRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) => Ok(JsString("success"))
        }
    }
  }

}

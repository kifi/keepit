package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.{ OrganizationCommander, OrganizationMembershipCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.util.{ Failure, Success }

@Singleton
class MobileOrganizationMembershipController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  // If userIdOpt is provided AND the user can invite members, return invited users as well as members
  def getMembers(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    if (limit > 30) {
      BadRequest(Json.obj("error" -> "invalid_limit"))
    } else Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val showInvitees = request.permissions.contains(OrganizationPermission.INVITE_MEMBERS)
        val membersAndMaybeInvitees = orgMembershipCommander.getMembersAndInvitees(orgId, Limit(limit), Offset(offset), includeInvitees = showInvitees)
        Ok(Json.obj("members" -> membersAndMaybeInvitees))
    }
  }

  def modifyMembers(pubId: PublicId[Organization]) = UserAction(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val membersToBeModified = (request.body \ "members").as[JsArray].value
        val modifyRequests = membersToBeModified map { memberMod =>
          val targetId = (memberMod \ "userId").as[Id[User]]
          val newRole = (memberMod \ "role").as[OrganizationRole]
          OrganizationMembershipModifyRequest(orgId, requesterId = request.userId, targetId = targetId, newRole = newRole)
        }

        orgMembershipCommander.modifyMemberships(modifyRequests) match {
          case Left(failure) => failure.asErrorResponse
          case Right(responses) =>
            val modifications = responses.keys.map(r => Json.obj("userId" -> r.targetId, "newRole" -> r.newRole))
            Ok(Json.obj("modifications" -> modifications))
        }
    }
  }
  def removeMembers(pubId: PublicId[Organization]) = UserAction(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val membersToBeRemoved = (request.body \ "members").as[Seq[Id[User]]]
        val removeRequests = for (targetId <- membersToBeRemoved) yield OrganizationMembershipRemoveRequest(orgId, request.userId, targetId)

        orgMembershipCommander.removeMemberships(removeRequests) match {
          case Left(failure) => failure.asErrorResponse
          case Right(responses) => Ok(Json.obj("removals" -> responses.keys.map(_.targetId)))
        }
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

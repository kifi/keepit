package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationMembershipCommander, UserCommander }
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json._

import scala.util.{ Failure, Success }

class MobileOrganizationMembershipController @Inject() (
    orgMemberCommander: OrganizationMembershipCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val config: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  private def sendFailResponse(fail: OrganizationFail) = Status(fail.status)(Json.obj("error" -> fail.message))

  // If userIdOpt is provided AND the user is the organization owner, return invited users as well as members
  def getMembers(pubId: PublicId[Organization], offset: Int, count: Int, userIdOpt: Option[Id[User]]) = {
    if (count > 30) {
      BadRequest(Json.obj("error" -> "invalid_count"))
    } else Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val showInvitees: Boolean = true // TODO: uncomment one of the lines below to get this working properly
        //val showInvitees: Boolean = userIdOpt.contains(orgCommander.getOwnerId(orgId))
        //val showInvitees: Boolean = userIdOpt.contains(orgCommander.get(orgId).ownerId)
        val membersAndMaybeInvitees = orgMemberCommander.getMembersAndInvitees(orgId, Count(count), Offset(offset), includeInvitees = showInvitees)
        Ok(Json.obj("members" -> membersAndMaybeInvitees))
    }
  }

  def modifyMembers(pubId: PublicId[Organization]) = ???
  /*
  def modifyMembers(pubId: PublicId[Organization]) = UserAction(parse.tolerantJson) { request =>
    // Format for the POST request is:
    // { members: [ {userId: USER_ID, access: NEW_ACCESS}, ... ]
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val modifications = (request.body \ "members").as[Seq[(Id[User], OrganizationAccess)]]
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        val res = orgMemberCommander.modifyMemberships(orgId, request.userId, modifications)
        res match {
          case Left(fail) => sendFailResponse(fail)
          case Right(_) => NoContent
        }
    }
  }
  */

  def removeMembers(pubId: PublicId[Organization]) = ???
  /*
  TODO: Right now orgMemberCommander.removeMembers returns a MemberRemovals.
        I think a Either[OrganizationFail, Seq[Id[User]]] or something is better
  def removeMembers(pubId: PublicId[Organization]) = UserAction(parse.tolerantJson) { request =>
    // Format for the POST request is:
    // { members: [ USER_ID1, USER_ID2, ... ]
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val membersToBeRemoved = (request.body \ "members").as[Seq[Id[User]]]
        implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
        val res = orgMemberCommander.removeMembers(orgId, request.userId, membersToBeRemoved)
        // case class MemberRemovals(failedToRemove: Seq[Id[User]], removed: Seq[Id[User]])
        res match {
          case Left(fail) => sendFailResponse(fail)
          case Right(_) => NoContent
        }
    }
  }
  */
}

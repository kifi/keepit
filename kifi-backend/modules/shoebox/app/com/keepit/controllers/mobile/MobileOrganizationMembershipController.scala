package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ OrganizationFail, OrganizationMembershipCommander, UserCommander }
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
        val membersAndMaybeInvitees = orgMemberCommander.getMembersAndInvitees(orgId, Limit(count), Offset(offset), includeInvitees = showInvitees)
        Ok(Json.obj("members" -> membersAndMaybeInvitees))
    }
  }

  def modifyMembers(pubId: PublicId[Organization]) = ???
  def removeMembers(pubId: PublicId[Organization]) = ???

}

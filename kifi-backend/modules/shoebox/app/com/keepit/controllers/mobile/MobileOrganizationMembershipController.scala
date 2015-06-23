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

  private def sendFailResponse(fail: OrganizationFail) = Status(fail.status)(Json.obj("error" -> fail.message))

  // If userIdOpt is provided AND the user can invite members, return invited users as well as members
  def getMembers(pubId: PublicId[Organization], offset: Int, limit: Int, userIdOpt: Option[Id[User]]) = (MaybeUserAction andThen OrganizationViewAction(pubId)) { request =>
    if (limit > 30) {
      BadRequest(Json.obj("error" -> "invalid_limit"))
    } else Organization.decodePublicId(pubId) match {
      case Failure(ex) => BadRequest(Json.obj("error" -> "invalid_organization_id"))
      case Success(orgId) =>
        val permissions = orgMembershipCommander.getPermissions(orgId, userIdOpt)
        val showInvitees = permissions.contains(OrganizationPermission.INVITE_MEMBERS)
        val membersAndMaybeInvitees = orgMembershipCommander.getMembersAndInvitees(orgId, Limit(limit), Offset(offset), includeInvitees = showInvitees)
        Ok(Json.obj("members" -> membersAndMaybeInvitees))
    }
  }

  def modifyMembers(pubId: PublicId[Organization]) = ???
  def removeMembers(pubId: PublicId[Organization]) = ???

}

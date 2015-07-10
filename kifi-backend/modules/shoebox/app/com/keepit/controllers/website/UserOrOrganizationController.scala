package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.json.Json
import com.keepit.shoebox.controllers.OrganizationAccessActions

import scala.concurrent.ExecutionContext

@Singleton
class UserOrOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    userProfileController: UserProfileController,
    orgController: OrganizationController,
    handleCommander: HandleCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def getByHandle(handle: Handle) = {
    val handleOwnerObjectOpt = db.readOnlyReplica { implicit session => handleCommander.getByHandle(handle) }
    val actionOpt = handleOwnerObjectOpt map {
      case (Left(org), _) => OrganizationAction(Organization.publicId(org.id.get), OrganizationPermission.VIEW_ORGANIZATION) { request =>
        val orgPayload = orgController.getOrganizationHelper(org.id.get)
        Ok(Json.obj("type" -> "org", "result" -> orgPayload))
      }
      case (Right(user), _) => MaybeUserAction { request =>
        val viewer = request.userOpt
        val userPayload = userProfileController.getProfileHelper(user.username, viewer).get
        Ok(Json.obj("type" -> "user", "result" -> userPayload))
      }
    }
    actionOpt.getOrElse { MaybeUserAction { request => NotFound } }
  }
}

package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.json.Json

import scala.concurrent.{ Future, ExecutionContext }

@Singleton
class UserOrOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    db: Database,
    userProfileController: UserProfileController,
    orgController: OrganizationController,
    handleCommander: HandleCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with ShoeboxServiceController {

  def getByHandle(handle: Handle) = MaybeUserAction.async { request =>
    val actionAndTypeOpt = db.readOnlyReplica { implicit session => handleCommander.getByHandle(handle) } map {
      case (Left(org), _) => (orgController.getOrganization(Organization.publicId(org.id.get)), "org")
      case (Right(user), _) => (userProfileController.getProfile(user.username), "user")
    }
    actionAndTypeOpt match {
      case None => Future.successful(NotFound(Json.obj("error" -> "handle_not_found")))
      case Some((action, actionType)) => action(request).map { result =>
        result.withHeaders("type" -> actionType)
      }
    }
  }
}

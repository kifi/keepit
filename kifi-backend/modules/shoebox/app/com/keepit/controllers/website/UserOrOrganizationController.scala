package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.Json
import com.keepit.shoebox.controllers.OrganizationAccessActions

import scala.concurrent.{ Future, ExecutionContext }

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

  def getByHandle(handle: Handle) = MaybeUserAction.async { request =>
    val handleOwnerObjectOpt = db.readOnlyReplica { implicit session => handleCommander.getByHandle(handle) }
    val actionAndTypeOpt = handleOwnerObjectOpt map {
      case (Left(org), _) =>
        (orgController.getOrganization(Organization.publicId(org.id.get)), "org")
      case (Right(user), _) =>
        (userProfileController.getProfile(user.username), "user")
    }
    actionAndTypeOpt.map {
      case (action, actionType) =>
        action(request).flatMap { result =>
          result.body.run(Iteratee.getChunks).map { chunks =>
            val payload = chunks.head
            Ok(Json.obj("type" -> actionType, "result" -> Json.parse(payload)))
          }
        }
    }.getOrElse(Future.successful(NotFound))
  }

}

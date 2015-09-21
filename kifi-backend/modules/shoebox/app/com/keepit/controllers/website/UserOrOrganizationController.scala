package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.iteratee.Iteratee
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc.Result

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Either, Success, Failure, Try }

@Singleton
class UserOrOrganizationController @Inject() (
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    userCommander: UserCommander,
    libraryController: LibraryController,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    userProfileController: UserProfileController,
    orgController: OrganizationController,
    handleCommander: HandleCommander,
    airbrake: AirbrakeNotifier,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def extractBody(result: Result): Future[Try[JsValue]] = {
    result.body.run(Iteratee.getChunks).map { chunks =>
      Try(Json.parse(chunks.head))
    }
  }

  def getHandleOwnerObjectOpt(handle: Handle): Option[(Either[Organization, User], Boolean)] = db.readOnlyReplica { implicit session => handleCommander.getByHandle(handle) }

  def getByHandle(handle: Handle, authToken: Option[String]) = MaybeUserAction.async { request =>
    val handleOwnerObjectOpt = getHandleOwnerObjectOpt(handle)
    handleOwnerObjectOpt match {
      case None => Future.successful(NotFound(Json.obj("error" -> "handle_not_found")))
      case Some(handleOwnerObject) =>
        val (action, actionType) = handleOwnerObject match {
          case (Left(org), _) =>
            (orgController.getOrganization(Organization.publicId(org.id.get), authToken), "org")
          case (Right(user), _) =>
            (userProfileController.getProfile(user.username), "user")
        }
        for (result <- action(request); bodyTry <- extractBody(result)) yield {
          bodyTry match {
            case Success(body) => Ok(Json.obj("type" -> actionType, "result" -> body))
            case Failure(ex) =>
              airbrake.notify(s"Could not parse the body in getByHandle($handle): $ex")
              BadRequest
          }
        }
    }
  }

  def getLibrariesByHandle(handle: Handle, page: Int, pageSize: Int, filter: String) = MaybeUserAction.async { request =>
    val handleOwnerObjectOpt = getHandleOwnerObjectOpt(handle)
    handleOwnerObjectOpt match {
      case None => Future.successful(NotFound(Json.obj("error" -> "handle_not_found")))
      case Some(handleOwnerObject) =>
        val (action, actionType) = handleOwnerObject match {
          case (Left(org), _) =>
            (orgController.getOrganizationLibraries(Organization.publicId(org.id.get), offset = page * pageSize, limit = pageSize), "org")
          case (Right(user), _) =>
            (userProfileController.getProfileLibraries(user.username, page, pageSize, filter), "user")
        }
        for (result <- action(request); bodyTry <- extractBody(result)) yield {
          bodyTry match {
            case Success(body) => Ok(Json.obj("type" -> actionType, "result" -> body))
            case Failure(ex) =>
              airbrake.notify("Could not parse the body in getLibrariesByHandle: " + ex)
              BadRequest
          }
        }
    }
  }
}

package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext
import scala.util.{ Success, Failure }

@Singleton
class MobileOrganizationController @Inject() (
    orgCommander: OrganizationCommander,
    orgInfoCommander: OrganizationInfoCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    libraryInfoCommander: LibraryInfoCommander,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  implicit val organizationViewWrites = OrganizationView.embeddedMembershipWrites

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.validate[OrganizationInitialValues](OrganizationInitialValues.mobileV1) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(initialValues, _) =>
        val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
        orgCommander.createOrganization(createRequest) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            val organizationView = orgInfoCommander.getOrganizationView(response.newOrg.id.get, request.userIdOpt, authTokenOpt = None)
            Ok(Json.toJson(organizationView))
        }
    }
  }

  def modifyOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.validate[OrganizationModifications](OrganizationModifications.mobileV1) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(modifications, _) =>
        orgCommander.modifyOrganization(OrganizationModifyRequest(request.request.userId, request.orgId, modifications)) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            val organizationView = orgInfoCommander.getOrganizationView(response.modifiedOrg.id.get, request.request.userIdOpt, authTokenOpt = None)
            Ok(Json.toJson(organizationView))
        }
    }
  }

  def deleteOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    val deleteRequest = OrganizationDeleteRequest(requesterId = request.request.userId, orgId = request.orgId)
    orgCommander.deleteOrganization(deleteRequest) match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => NoContent
    }
  }

  def getOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    val organizationView = orgInfoCommander.getOrganizationView(request.orgId, request.request.userIdOpt, authTokenOpt = None)
    Ok(Json.toJson(organizationView))
  }

  def getOrganizationLibraries(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.obj("libraries" -> Json.toJson(libraryInfoCommander.getOrganizationLibrariesVisibleToUser(request.orgId, request.request.userIdOpt, Offset(offset), Limit(limit)))))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = MaybeUserAction { request =>
    val user = userCommander.getByExternalId(extId)
    val visibleOrgs = orgMembershipCommander.getVisibleOrganizationsForUser(user.id.get, viewerIdOpt = request.userIdOpt)

    val orgViewsMap = orgInfoCommander.getOrganizationViews(visibleOrgs.toSet, request.userIdOpt, authTokenOpt = None)

    val orgViews = visibleOrgs.map(org => orgViewsMap(org))

    Ok(Json.obj("organizations" -> orgViews))
  }

  def sendCreateTeamEmail(email: String) = UserAction { request =>
    EmailAddress.validate(email) match {
      case Failure(err) => BadRequest(Json.obj("error" -> "invalid_email"))
      case Success(validEmail) =>
        userCommander.sendCreateTeamEmail(request.userId, validEmail) match {
          case Left(err) => Forbidden(err)
          case _ => Ok
        }
    }
  }
}

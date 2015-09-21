package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@Singleton
class MobileOrganizationController @Inject() (
    orgCommander: OrganizationCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  implicit val organizationViewWrites = OrganizationView.mobileWrites

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.mobile).build
    request.body.validate[OrganizationInitialValues](OrganizationInitialValues.mobileV1) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(initialValues, _) =>
        val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
        orgCommander.createOrganization(createRequest) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            val organizationView = orgCommander.getOrganizationView(response.newOrg.id.get, request.userIdOpt, authTokenOpt = None)
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
            val organizationView = orgCommander.getOrganizationView(response.modifiedOrg.id.get, request.request.userIdOpt, authTokenOpt = None)
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
    val organizationView = orgCommander.getOrganizationView(request.orgId, request.request.userIdOpt, authTokenOpt = None)
    Ok(Json.toJson(organizationView))
  }

  def getOrganizationLibraries(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.obj("libraries" -> Json.toJson(orgCommander.getOrganizationLibrariesVisibleToUser(request.orgId, request.request.userIdOpt, Offset(offset), Limit(limit)))))
  }

  // TODO(ryan): when organizations are no longer hidden behind an experiment, change this to a MaybeUserAction
  def getOrganizationsForUser(extId: ExternalId[User]) = MaybeUserAction { request =>
    val user = userCommander.getByExternalId(extId)
    val visibleOrgs = orgMembershipCommander.getVisibleOrganizationsForUser(user.id.get, viewerIdOpt = request.userIdOpt)

    val orgViewsMap = orgCommander.getOrganizationViews(visibleOrgs.toSet, request.userIdOpt, authTokenOpt = None)

    val orgViews = visibleOrgs.map(org => orgViewsMap(org))

    Ok(Json.obj("organizations" -> orgViews))
  }
}

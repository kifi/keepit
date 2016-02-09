package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders.LibraryQuery.Arrangement
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@Singleton
class OrganizationController @Inject() (
    orgCommander: OrganizationCommander,
    orgInfoCommander: OrganizationInfoCommander,
    orgMembershipCommander: OrganizationMembershipCommander,
    orgInviteCommander: OrganizationInviteCommander,
    userCommander: UserCommander,
    libraryInfoCommander: LibraryInfoCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    airbrake: AirbrakeNotifier,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  implicit val organizationViewWrites = OrganizationView.defaultWrites

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    request.body.validate[OrganizationInitialValues](OrganizationInitialValues.website) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(initialValues, _) =>
        val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
        orgCommander.createOrganization(createRequest) match {
          case Left(failure) =>
            failure.asErrorResponse
          case Right(response) =>
            Ok(Json.toJson(response.orgView))
        }
    }
  }

  def modifyOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    request.body.validate[OrganizationModifications](OrganizationModifications.website) match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate modify request from ${request.request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(modifications, _) =>
        orgCommander.modifyOrganization(OrganizationModifyRequest(request.request.userId, request.orgId, modifications)) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            Ok(Json.toJson(response.orgView))
        }
    }
  }

  def transferOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    (request.body \ "newOwner").validate[ExternalId[User]] match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate transfer request from ${request.request.userId}: ${request.body}", new JsResultException(errs))
        BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(newOwnerExtId, _) =>
        val newOwner = userCommander.getByExternalId(newOwnerExtId)
        val transferRequest = OrganizationTransferRequest(requesterId = request.request.userId, orgId = request.orgId, newOwner = newOwner.id.get)
        orgCommander.transferOrganization(transferRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) => NoContent
        }
    }
  }

  def deleteOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfoAndSource(request, KeepSource.site).build
    val deleteRequest = OrganizationDeleteRequest(requesterId = request.request.userId, orgId = request.orgId)
    orgCommander.deleteOrganization(deleteRequest) match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => NoContent
    }
  }

  def getOrganization(pubId: PublicId[Organization], authTokenOpt: Option[String] = None) = OrganizationAction(pubId, authTokenOpt, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    val organizationView = orgInfoCommander.getOrganizationView(request.orgId, request.request.userIdOpt, authTokenOpt)
    Ok(Json.toJson(organizationView))
  }

  def getOrganizationLibraries(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    val useCustomLibraryOrderingLogic = request.request match {
      case ur: UserRequest[_] if ur.experiments.contains(UserExperimentType.CUSTOM_LIBRARY_ORDERING) => true
      case _ => false
    }
    val libCards = if (useCustomLibraryOrderingLogic) {
      libraryInfoCommander.rpbGetOrgLibraries(request.request.userIdOpt, request.orgId, None, offset = offset, limit = limit)
    } else {
      libraryInfoCommander.getOrganizationLibrariesVisibleToUser(request.orgId, request.request.userIdOpt, Offset(offset), Limit(limit))
    }
    Ok(Json.obj("libraries" -> libCards))
  }

  def getBasicLibrariesForOrg(pubId: PublicId[Organization], orderingOpt: Option[String], directionOpt: Option[String], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    val arrangement = for {
      ordering <- orderingOpt.flatMap(LibraryOrdering.fromStr)
      direction <- directionOpt.flatMap(SortDirection.fromStr)
    } yield Arrangement(ordering, direction)
    val basicLibs = db.readOnlyMaster(implicit s => libraryInfoCommander.getBasicLibrariesForOrg(request.orgId, request.request.userIdOpt, arrangement, fromIdOpt = None, offset, limit))
    Ok(Json.obj("libraries" -> basicLibs))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = MaybeUserAction { request =>
    val user = userCommander.getByExternalIds(Seq(extId)).values.head
    val visibleOrgs = orgMembershipCommander.getVisibleOrganizationsForUser(user.id.get, viewerIdOpt = request.userIdOpt)
    val orgCards = orgInfoCommander.getOrganizationInfos(visibleOrgs.toSet, request.userIdOpt).values.toSeq

    Ok(Json.obj("organizations" -> Json.toJson(orgCards)))
  }
}

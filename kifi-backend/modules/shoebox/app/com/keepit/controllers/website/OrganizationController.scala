package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.{ HeimdalContextBuilder, HeimdalContextBuilderFactory }
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@Singleton
class OrganizationController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    val orgInviteCommander: OrganizationInviteCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    airbrake: AirbrakeNotifier,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  implicit val organizationViewWrites = FullOrganizationView.defaultWrites

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
            val organizationView = orgCommander.getFullOrganizationView(response.newOrg.id.get, request.userIdOpt, authTokenOpt = None)
            Ok(Json.toJson(organizationView))
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
            val organizationView = orgCommander.getFullOrganizationView(response.modifiedOrg.id.get, request.request.userIdOpt, authTokenOpt = None)
            Ok(Json.toJson(organizationView))
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
    val organizationView = orgCommander.getFullOrganizationView(request.orgId, request.request.userIdOpt, authTokenOpt)
    Ok(Json.toJson(organizationView))
  }

  def getOrganizationLibraries(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, authTokenOpt = None, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.obj("libraries" -> Json.toJson(orgCommander.getOrganizationLibrariesVisibleToUser(request.orgId, request.request.userIdOpt, Offset(offset), Limit(limit)))))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = MaybeUserAction { request =>
    val user = userCommander.getByExternalIds(Seq(extId)).values.head
    val visibleOrgs = orgMembershipCommander.getVisibleOrganizationsForUser(user.id.get, viewerIdOpt = request.userIdOpt)
    val orgCards = orgCommander.getOrganizationInfos(visibleOrgs.toSet, request.userIdOpt).values.toSeq

    Ok(Json.obj("organizations" -> Json.toJson(orgCards)))
  }
}

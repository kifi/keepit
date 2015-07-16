package com.keepit.controllers.website

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@Singleton
class OrganizationController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    if (!request.experiments.contains(UserExperimentType.ORGANIZATION)) BadRequest(Json.obj("error" -> "insufficient_permissions"))
    else {
      request.body.validate[OrganizationInitialValues](OrganizationInitialValues.website) match {
        case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
        case JsSuccess(initialValues, _) =>
          val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
          orgCommander.createOrganization(createRequest) match {
            case Left(failure) =>
              failure.asErrorResponse
            case Right(response) =>
              val orgView = orgCommander.getOrganizationView(response.newOrg.id.get, request.userIdOpt)
              implicit val writes = OrganizationView.website
              Ok(Json.obj("organization" -> Json.toJson(orgView)))
          }
      }
    }
  }

  def modifyOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    request.body.validate[OrganizationModifications](OrganizationModifications.website) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(modifications, _) =>
        orgCommander.modifyOrganization(OrganizationModifyRequest(request.request.userId, request.orgId, modifications)) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            val orgView = orgCommander.getOrganizationView(response.modifiedOrg.id.get, request.request.userIdOpt)
            implicit val writes = OrganizationView.website
            Ok(Json.obj("organization" -> Json.toJson(orgView)))
        }
    }
  }

  def deleteOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION) { request =>
    val deleteRequest = OrganizationDeleteRequest(requesterId = request.request.userId, orgId = request.orgId)
    orgCommander.deleteOrganization(deleteRequest) match {
      case Left(fail) => fail.asErrorResponse
      case Right(response) => NoContent
    }
  }

  def getOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    val orgView = orgCommander.getOrganizationView(request.orgId, request.request.userIdOpt)
    val requesterPermissions = Json.toJson(orgMembershipCommander.getPermissions(request.orgId, request.request.userIdOpt))
    Ok(Json.obj("organization" -> Json.toJson(orgView)(OrganizationView.website), "viewer_permissions" -> requesterPermissions))
  }

  def getOrganizationLibraries(pubId: PublicId[Organization], offset: Int, limit: Int) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.obj("libraries" -> Json.toJson(orgCommander.getLibrariesVisibleToUser(request.orgId, request.request.userIdOpt, Offset(offset), Limit(limit)))))
  }

  // TODO(ryan): when organizations are no longer hidden behind an experiment, change this to a MaybeUserAction
  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    if (!request.experiments.contains(UserExperimentType.ORGANIZATION)) BadRequest(Json.obj("error" -> "insufficient_permissions"))
    else {
      val user = userCommander.getByExternalIds(Seq(extId)).values.head
      val visibleOrgs = orgMembershipCommander.getVisibleOrganizationsForUser(user.id.get, viewerIdOpt = request.userIdOpt)
      val orgCards = orgCommander.getOrganizationCards(visibleOrgs, request.userIdOpt).values.toSeq

      implicit val writes = OrganizationCard.website
      Ok(Json.obj("organizations" -> Json.toJson(orgCards)))
    }
  }
}

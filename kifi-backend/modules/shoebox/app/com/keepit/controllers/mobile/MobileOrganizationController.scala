package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.store.S3ImageConfig
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.ExecutionContext

@Singleton
class MobileOrganizationController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    userCommander: UserCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val imageConfig: S3ImageConfig,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    if (!request.experiments.contains(ExperimentType.ORGANIZATION)) BadRequest(Json.obj("error" -> "insufficient_permissions"))
    else {
      request.body.validate[OrganizationInitialValues](OrganizationInitialValues.mobileV1) match {
        case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
        case JsSuccess(initialValues, _) =>
          val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
          orgCommander.createOrganization(createRequest) match {
            case Left(failure) =>
              failure.asErrorResponse
            case Right(response) =>
              val orgView = orgCommander.getOrganizationView(response.newOrg.id.get)
              implicit val writes = OrganizationView.mobileV1
              Ok(Json.obj("organization" -> Json.toJson(orgView)))
          }
      }
    }
  }

  def modifyOrganization(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    request.body.validate[OrganizationModifications](OrganizationModifications.mobileV1) match {
      case _: JsError => OrganizationFail.BAD_PARAMETERS.asErrorResponse
      case JsSuccess(modifications, _) =>
        orgCommander.modifyOrganization(OrganizationModifyRequest(request.request.userId, request.orgId, modifications)) match {
          case Left(failure) => failure.asErrorResponse
          case Right(response) =>
            val orgView = orgCommander.getOrganizationView(response.modifiedOrg.id.get)
            implicit val writes = OrganizationView.mobileV1
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
    Ok(Json.obj("organization" -> Json.toJson(orgCommander.getOrganizationView(request.orgId))(OrganizationView.mobileV1)))
  }

  // TODO(ryan): when organizations are no longer hidden behind an experiment, change this to a MaybeUserAction
  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    if (!request.experiments.contains(ExperimentType.ORGANIZATION)) BadRequest(Json.obj("error" -> "insufficient_permissions"))
    else {
      val user = userCommander.getByExternalIds(Seq(extId)).values.head
      val publicOrgs = orgMembershipCommander.getAllOrganizationsForUser(user.id.get)
      val orgCards = orgCommander.getOrganizationCards(publicOrgs).values.toSeq

      implicit val writes = OrganizationCard.mobileV1
      Ok(Json.obj("organizations" -> Json.toJson(orgCards)))
    }
  }
}

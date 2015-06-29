package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json.{ JsError, JsSuccess, Json }

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

@Singleton
class MobileOrganizationController @Inject() (
    val orgCommander: OrganizationCommander,
    val orgMembershipCommander: OrganizationMembershipCommander,
    heimdalContextBuilder: HeimdalContextBuilderFactory,
    val userActionsHelper: UserActionsHelper,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  // TODO: add to commander:
  // getOrganizationCard

  def createOrganization = UserAction(parse.tolerantJson) { request =>
    if (!request.experiments.contains(ExperimentType.ORGANIZATION)) BadRequest(Json.obj("error" -> "insufficient_permissions"))
    else {
      request.body.validate[OrganizationModifications] match {
        case _: JsError =>
          BadRequest
        case JsSuccess(initialValues, _) =>
          val createRequest = OrganizationCreateRequest(requesterId = request.userId, initialValues)
          orgCommander.createOrganization(createRequest) match {
            case Left(failure) =>
              failure.asErrorResponse
            case Right(response) =>
              Ok(Json.toJson(orgCommander.getFullOrganizationInfo(response.newOrg.id.get)))
          }
      }
    }
  }

  def modifyOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.EDIT_ORGANIZATION)(parse.tolerantJson) { request =>
    request.request.userIdOpt match {
      case None => OrganizationFail.NOT_A_MEMBER.asErrorResponse
      case Some(requesterId) =>
        request.body.asOpt[OrganizationModifications] match {
          case Some(modifications) =>
            orgCommander.modifyOrganization(OrganizationModifyRequest(requesterId, request.orgId, modifications)) match {
              case Left(failure) => failure.asErrorResponse
              case Right(response) => Ok(Json.toJson(orgCommander.getFullOrganizationInfo(request.orgId)))
            }
          case _ => OrganizationFail.BAD_PARAMETERS.asErrorResponse
        }
    }
  }

  def deleteOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.EDIT_ORGANIZATION) { request =>
    request.request.userIdOpt match {
      case None => OrganizationFail.INSUFFICIENT_PERMISSIONS.asErrorResponse
      case Some(requesterId) =>
        val deleteRequest = OrganizationDeleteRequest(requesterId = requesterId, orgId = request.orgId)
        orgCommander.deleteOrganization(deleteRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(response) => NoContent
        }
    }
  }

  def getOrganization(pubId: PublicId[Organization]) = OrganizationAction(pubId, OrganizationPermission.VIEW_ORGANIZATION) { request =>
    Ok(Json.toJson(orgCommander.getFullOrganizationInfo(request.orgId)))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    // TODO: provide a Json thing for a bunch of OrganizationCards
    Ok
  }
}

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
import scala.util.{Success, Failure}

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

  def modifyOrganization(pubId: PublicId[Organization]) = UserAction.async(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_organization_id")))
      case Success(orgId) =>
        request.body.validate[OrganizationModifications] match {
          case _: JsError => Future.successful(BadRequest(Json.obj("error" -> "badly_formed_modifications")))
          case JsSuccess(modifications, _) =>
            orgCommander.modifyOrganization(OrganizationModifyRequest(request.userId, orgId, modifications)) match {
              case Left(failure) => Future.successful(failure.asErrorResponse)
              case Right(response) => Future.successful(Ok(Json.toJson(response)))
            }
        }
    }
  }

  def deleteOrganization(pubId: PublicId[Organization]) = UserAction.async(parse.tolerantJson) { request =>
    Organization.decodePublicId(pubId) match {
      case Failure(ex) => Future.successful(BadRequest(Json.obj("error" -> "invalid_organization_id")))
      case Success(orgId) =>
        val deleteRequest = OrganizationDeleteRequest(requesterId = request.userId, orgId = orgId)
        orgCommander.deleteOrganization(deleteRequest) match {
          case Left(fail) => Future.successful(fail.asErrorResponse)
          case Right(response) => Future.successful(Ok(Json.toJson(response)))
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

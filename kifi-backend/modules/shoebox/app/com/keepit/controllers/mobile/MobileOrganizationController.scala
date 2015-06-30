package com.keepit.controllers.mobile

import com.google.inject.{ Inject, Singleton }
import com.keepit.commanders._
import com.keepit.common.controller._
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.ExternalId
import com.keepit.common.store.{ S3ImageConfig, ImagePath }
import com.keepit.heimdal.HeimdalContextBuilderFactory
import com.keepit.model._
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Success, Failure }

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

  private def serializeFullOrganizationInfo(fullInfo: FullOrganizationInfo): JsValue = {
    Json.obj(
      "id" -> Organization.publicId(fullInfo.orgId),
      "handle" -> fullInfo.handle,
      "name" -> fullInfo.name,
      "description" -> fullInfo.description,
      "avatarPath" -> fullInfo.avatarPath.map(_.getUrl),
      "members" -> fullInfo.members,
      "memberCount" -> fullInfo.memberCount,
      "publicLibraries" -> fullInfo.publicLibraries,
      "organizationLibraries" -> fullInfo.organizationLibraries,
      "secretLibraries" -> fullInfo.secretLibraries
    )
  }
  private def serializeTinyOrganizationInfo(tinyInfo: FullOrganizationInfo): JsValue = {
    Json.obj(
      "id" -> Organization.publicId(tinyInfo.orgId),
      "handle" -> tinyInfo.handle,
      "name" -> tinyInfo.name,
      "avatarPath" -> tinyInfo.avatarPath.map(_.getUrl),
      "memberCount" -> tinyInfo.memberCount,
      "publicLibraries" -> tinyInfo.publicLibraries
    )
  }

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
              val fullInfo = orgCommander.getFullOrganizationInfo(response.newOrg.id.get)
              Ok(serializeFullOrganizationInfo(fullInfo))
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
              case Right(response) =>
                val fullInfo = orgCommander.getFullOrganizationInfo(response.modifiedOrg.id.get)
                Ok(serializeFullOrganizationInfo(fullInfo))
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
    Ok(serializeFullOrganizationInfo(orgCommander.getFullOrganizationInfo(request.orgId)))
  }

  def getOrganizationsForUser(extId: ExternalId[User]) = UserAction { request =>
    val user = userCommander.getByExternalIds(Seq(extId)).values.head
    val publicOrgs = orgMembershipCommander.getAllOrganizationsForUser(user.id.get)
    val orgInfos = orgCommander.getFullOrganizationInfos(publicOrgs)
    Ok(JsArray(orgInfos.values.toSeq.map(serializeTinyOrganizationInfo)))
  }
}

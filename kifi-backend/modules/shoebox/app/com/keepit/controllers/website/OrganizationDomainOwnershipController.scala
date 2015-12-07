package com.keepit.controllers.website

import com.google.inject.{ Singleton, Inject }
import com.keepit.commanders.{ PermissionCommander, OrganizationDomainOwnershipCommander }
import com.keepit.common.controller.{ UserActionsHelper, ShoeboxServiceController, UserActions }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.slick.Database
import com.keepit.common.mail.EmailAddress
import com.keepit.model.{ OrganizationDomainSendMemberConfirmationRequest, OrganizationDomainRemoveRequest, OrganizationDomainAddRequest, OrganizationPermission, Organization }
import com.keepit.shoebox.controllers.OrganizationAccessActions
import play.api.libs.json.{ JsSuccess, Json, JsError }

import scala.util.{ Success, Failure }

@Singleton
class OrganizationDomainOwnershipController @Inject() (
    orgDomainOwnershipCommander: OrganizationDomainOwnershipCommander,
    val userActionsHelper: UserActionsHelper,
    val db: Database,
    val permissionCommander: PermissionCommander,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with OrganizationAccessActions with ShoeboxServiceController {

  def getDomains(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN) { request =>
    Ok(Json.toJson(orgDomainOwnershipCommander.getDomainsOwned(request.orgId)))
  }

  def addDomain(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { request =>
    (request.body \ "domain").validate[String] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(domain, _) =>
        val domainRequest = OrganizationDomainAddRequest(request.request.userId, request.orgId, domain)
        orgDomainOwnershipCommander.addDomainOwnership(domainRequest) match {
          case Left(fail) => fail.asErrorResponse
          case Right(success) => Ok(Json.obj("domain" -> success.domain.value))
        }
    }
  }

  def removeDomain(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { request =>
    (request.body \ "domain").validate[String] match {
      case JsError(errs) => BadRequest(Json.obj("error" -> "badly_formatted_request"))
      case JsSuccess(domain, _) =>
        val domainRequest = OrganizationDomainRemoveRequest(request.request.userId, request.orgId, domain)
        orgDomainOwnershipCommander.removeDomainOwnership(domainRequest) match {
          case Some(fail) => fail.asErrorResponse
          case None => Ok
        }
    }
  }

  def addDomainOwnershipAfterVerification(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.MANAGE_PLAN)(parse.tolerantJson) { implicit request =>
    val email = (request.body \ "email").as[EmailAddress]
    EmailAddress.validate(email.address) match {
      case Failure(fail) => BadRequest("invalid_email_format")
      case Success(validEmail) =>
        orgDomainOwnershipCommander.addPendingOwnershipByEmail(request.orgId, request.request.userId, validEmail) match {
          case Some(fail) => fail.asErrorResponse
          case None => Ok
        }
    }
  }

  def sendMemberConfirmationEmail(pubId: PublicId[Organization]) = OrganizationUserAction(pubId, OrganizationPermission.JOIN_BY_VERIFYING)(parse.tolerantJson) { implicit request =>
    val email = (request.body \ "email").as[EmailAddress]
    orgDomainOwnershipCommander.sendMembershipConfirmationEmail(OrganizationDomainSendMemberConfirmationRequest(request.request.userId, request.orgId, email)) match {
      case Left(fail) => fail.asErrorResponse
      case Right(_) => Ok
    }
  }
}

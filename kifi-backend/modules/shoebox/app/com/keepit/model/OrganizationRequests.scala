package com.keepit.model

import com.keepit.classify.NormalizedHostname
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import play.api.http.Status._
import play.api.libs.json._
import play.api.mvc.Results.Status

import scala.concurrent.Future
import scala.util.control.NoStackTrace

sealed abstract class OrganizationRequest

case class OrganizationCreateRequest(requesterId: Id[User], initialValues: OrganizationInitialValues) extends OrganizationRequest
case class OrganizationCreateResponse(request: OrganizationCreateRequest, newOrg: Organization, orgGeneralLibrary: Library, orgView: OrganizationView)

case class OrganizationModifyRequest(requesterId: Id[User], orgId: Id[Organization], modifications: OrganizationModifications) extends OrganizationRequest
case class OrganizationModifyResponse(request: OrganizationModifyRequest, modifiedOrg: Organization, orgView: OrganizationView)

case class OrganizationSettingsRequest(orgId: Id[Organization], requesterId: Id[User], settings: OrganizationSettings) extends OrganizationRequest
case class OrganizationSettingsResponse(config: OrganizationConfiguration)

case class OrganizationDeleteRequest(requesterId: Id[User], orgId: Id[Organization]) extends OrganizationRequest
case class OrganizationDeleteResponse(request: OrganizationDeleteRequest, returningLibsFut: Future[Unit], deletingLibsFut: Future[Unit])

case class OrganizationTransferRequest(requesterId: Id[User], orgId: Id[Organization], newOwner: Id[User]) extends OrganizationRequest
case class OrganizationTransferResponse(request: OrganizationTransferRequest, modifiedOrg: Organization)

sealed abstract class OrganizationDomainRequest extends OrganizationRequest {
  def requesterId: Id[User]
  def orgId: Id[Organization]
  def domain: String
}

case class OrganizationDomainAddRequest(requesterId: Id[User], orgId: Id[Organization], domain: String) extends OrganizationDomainRequest
case class OrganizationDomainAddResponse(request: OrganizationDomainAddRequest, domain: NormalizedHostname)

case class OrganizationDomainPendingAddRequest(requesterId: Id[User], orgId: Id[Organization], domain: String) extends OrganizationDomainRequest
case class OrganizationDomainSendMemberConfirmationRequest(requesterId: Id[User], orgId: Id[Organization], email: EmailAddress) extends OrganizationDomainRequest {
  def domain = email.hostname
}

case class OrganizationDomainRemoveRequest(requesterId: Id[User], orgId: Id[Organization], domain: String) extends OrganizationDomainRequest
case class OrganizationDomainRemoveResponse(request: OrganizationDomainRemoveRequest, domain: NormalizedHostname)

sealed abstract class OrganizationMembershipRequest {
  def orgId: Id[Organization]
  def requesterId: Id[User]
  def targetId: Id[User]
}

case class OrganizationMembershipAddRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole = OrganizationRole.MEMBER) extends OrganizationMembershipRequest

case class OrganizationMembershipModifyRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole) extends OrganizationMembershipRequest

case class OrganizationMembershipRemoveRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User]) extends OrganizationMembershipRequest

case class OrganizationMembershipAddResponse(request: OrganizationMembershipAddRequest, membership: OrganizationMembership)
case class OrganizationMembershipModifyResponse(request: OrganizationMembershipModifyRequest, membership: OrganizationMembership)
case class OrganizationMembershipRemoveResponse(request: OrganizationMembershipRemoveRequest)

sealed abstract class OrganizationInviteRequest {
  def orgId: Id[Organization]
  def requesterId: Id[User]
}

case class OrganizationInviteSendRequest(orgId: Id[Organization], requesterId: Id[User], targetEmails: Set[EmailAddress], targetUserIds: Set[Id[User]], message: Option[String] = None) extends OrganizationInviteRequest
case class OrganizationInviteCancelRequest(orgId: Id[Organization], requesterId: Id[User], targetEmails: Set[EmailAddress], targetUserIds: Set[Id[User]]) extends OrganizationInviteRequest

case class OrganizationInviteSendResponse(request: OrganizationInviteSendRequest)
case class OrganizationInviteCancelResponse(request: OrganizationInviteCancelRequest, cancelledEmails: Set[EmailAddress], cancelledUserIds: Set[Id[User]])

sealed abstract class OrganizationFail(val status: Int, val message: String) extends Exception(message) with NoStackTrace {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

//TODO: when modifying these, make sure we do not break existing Mobile Controllers that are calling .asErrorResponse. Preferably don't modify, just add as needed.
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(FORBIDDEN, "insufficient_permissions")
  case object HANDLE_UNAVAILABLE extends OrganizationFail(CONFLICT, "handle_unavailable")
  case object NOT_A_MEMBER extends OrganizationFail(FORBIDDEN, "not_a_member")
  case object NO_VALID_INVITATIONS extends OrganizationFail(BAD_REQUEST, "no_valid_invitations")
  case object INVALID_PUBLIC_ID extends OrganizationFail(BAD_REQUEST, "invalid_public_id")
  case object BAD_PARAMETERS extends OrganizationFail(BAD_REQUEST, "bad_parameters")
  case object INVALID_MODIFICATIONS extends OrganizationFail(BAD_REQUEST, "invalid_modifications")
  case object INVALID_MODIFY_NAME extends OrganizationFail(BAD_REQUEST, "invalid_modifications_name")
  case object INVALID_MODIFY_PERMISSIONS extends OrganizationFail(BAD_REQUEST, "invalid_modifications_permissions")
  case object INVALID_MODIFY_SITEURL extends OrganizationFail(BAD_REQUEST, "invalid_modifications_name")
  case object INVITATION_NOT_FOUND extends OrganizationFail(BAD_REQUEST, "invitation_not_found_siteurl")
  case object ALREADY_A_MEMBER extends OrganizationFail(BAD_REQUEST, "already_a_member")
  case object INVALID_AUTHTOKEN extends OrganizationFail(UNAUTHORIZED, "invalid_authtoken")
  case object MODIFYING_UNEDITABLE_SETTINGS extends OrganizationFail(FORBIDDEN, "modifying_uneditable_settings")
  case object INVALID_DOMAIN_NAME extends OrganizationFail(BAD_REQUEST, "invalid_domain_name")
  case object DOMAIN_IS_EMAIL_PROVIDER extends OrganizationFail(FORBIDDEN, "domain_is_email_provider")
  case object DOMAIN_OWNERSHIP_NOT_FOUND extends OrganizationFail(NOT_FOUND, "domain_ownership_not_found")
  case object UNVERIFIED_EMAIL_DOMAIN extends OrganizationFail(FORBIDDEN, "unverified_email_domain")
  case object EMAIL_NOT_FOUND extends OrganizationFail(NOT_FOUND, "email_not_found")

  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case HANDLE_UNAVAILABLE.message => HANDLE_UNAVAILABLE
      case NOT_A_MEMBER.message => NOT_A_MEMBER
      case NO_VALID_INVITATIONS.message => NO_VALID_INVITATIONS
      case INVALID_PUBLIC_ID.message => INVALID_PUBLIC_ID
      case BAD_PARAMETERS.message => BAD_PARAMETERS
      case INVALID_MODIFICATIONS.message => INVALID_MODIFICATIONS
      case INVALID_MODIFY_NAME.message => INVALID_MODIFY_NAME
      case INVALID_MODIFY_PERMISSIONS.message => INVALID_MODIFY_PERMISSIONS
      case INVALID_MODIFY_SITEURL.message => INVALID_MODIFY_SITEURL
      case INVITATION_NOT_FOUND.message => INVITATION_NOT_FOUND
      case ALREADY_A_MEMBER.message => ALREADY_A_MEMBER
      case INVALID_AUTHTOKEN.message => INVALID_AUTHTOKEN
      case MODIFYING_UNEDITABLE_SETTINGS.message => MODIFYING_UNEDITABLE_SETTINGS
      case INVALID_DOMAIN_NAME.message => INVALID_DOMAIN_NAME
      case DOMAIN_IS_EMAIL_PROVIDER.message => DOMAIN_IS_EMAIL_PROVIDER
      case DOMAIN_OWNERSHIP_NOT_FOUND.message => DOMAIN_OWNERSHIP_NOT_FOUND
      case UNVERIFIED_EMAIL_DOMAIN.message => UNVERIFIED_EMAIL_DOMAIN
      case EMAIL_NOT_FOUND.message => EMAIL_NOT_FOUND
    }
  }
}

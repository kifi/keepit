package com.keepit.model

import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import play.api.http.Status._
import play.api.libs.json._
import play.api.mvc.Results.Status

sealed abstract class OrganizationRequest

case class OrganizationCreateRequest(requesterId: Id[User], initialValues: OrganizationInitialValues) extends OrganizationRequest
case class OrganizationCreateResponse(request: OrganizationCreateRequest, newOrg: Organization)

case class OrganizationModifyRequest(requesterId: Id[User], orgId: Id[Organization], modifications: OrganizationModifications) extends OrganizationRequest
case class OrganizationModifyResponse(request: OrganizationModifyRequest, modifiedOrg: Organization)

case class OrganizationDeleteRequest(requesterId: Id[User], orgId: Id[Organization]) extends OrganizationRequest
case class OrganizationDeleteResponse(request: OrganizationDeleteRequest)

case class OrganizationTransferRequest(requesterId: Id[User], orgId: Id[Organization], newOwner: Id[User]) extends OrganizationRequest
case class OrganizationTransferResponse(request: OrganizationTransferRequest, modifiedOrg: Organization)

case class OrganizationMemberInvitation(invited: Either[Id[User], EmailAddress], role: OrganizationRole, msgOpt: Option[String] = None)

sealed abstract class OrganizationMembershipRequest {
  def orgId: Id[Organization]
  def requesterId: Id[User]
  def targetId: Id[User]
}

case class OrganizationMembershipAddRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole) extends OrganizationMembershipRequest

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

sealed abstract class OrganizationFail(val status: Int, val message: String) {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

//TODO: when modifying these, make sure we do not break existing Mobile Controllers that are calling .asErrorResponse. Preferably don't modify, just add as needed.
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(FORBIDDEN, "insufficient_permissions")
  case object HANDLE_UNAVAILABLE extends OrganizationFail(CONFLICT, "handle_unavailable")
  case object NOT_A_MEMBER extends OrganizationFail(UNAUTHORIZED, "not_a_member")
  case object NO_VALID_INVITATIONS extends OrganizationFail(BAD_REQUEST, "no_valid_invitations")
  case object INVALID_PUBLIC_ID extends OrganizationFail(BAD_REQUEST, "invalid_public_id")
  case object BAD_PARAMETERS extends OrganizationFail(BAD_REQUEST, "bad_parameters")
  case object POKE_ON_COOLDOWN extends OrganizationFail(FORBIDDEN, "poke_on_cooldown")
  case object ALREADY_A_MEMBER extends OrganizationFail(FORBIDDEN, "already_a_member")
  case object ALREADY_INVITED extends OrganizationFail(FORBIDDEN, "already_invited")

  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case HANDLE_UNAVAILABLE.message => HANDLE_UNAVAILABLE
      case NOT_A_MEMBER.message => NOT_A_MEMBER
      case NO_VALID_INVITATIONS.message => NO_VALID_INVITATIONS
      case INVALID_PUBLIC_ID.message => INVALID_PUBLIC_ID
      case BAD_PARAMETERS.message => BAD_PARAMETERS
      case POKE_ON_COOLDOWN.message => POKE_ON_COOLDOWN
      case ALREADY_A_MEMBER.message => ALREADY_A_MEMBER
      case ALREADY_INVITED.message => ALREADY_INVITED
    }
  }
}

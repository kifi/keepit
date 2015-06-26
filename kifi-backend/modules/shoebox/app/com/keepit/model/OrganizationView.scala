package com.keepit.model

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.ImagePath
import com.kifi.macros.json
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.mvc.Results.Status

@json case class OrganizationImageInfo(path: ImagePath)

object OrganizationImageInfo {
  def createInfo(img: OrganizationAvatar) = OrganizationImageInfo(img.imagePath)
}

@json
case class OrganizationNotificationInfo(
  id: PublicId[Organization],
  name: String,
  handle: Option[PrimaryOrganizationHandle],
  image: Option[OrganizationImageInfo])

object OrganizationNotificationInfo {
  def fromOrganization(org: Organization, image: Option[OrganizationAvatar])(implicit config: PublicIdConfiguration): OrganizationNotificationInfo = {
    OrganizationNotificationInfo(Organization.publicId(org.id.get), org.name, org.handle, image.map(OrganizationImageInfo.createInfo(_)))
  }
}

case class OrganizationMemberInvitation(invited: Either[Id[User], EmailAddress], role: OrganizationRole, msgOpt: Option[String] = None)

sealed abstract class OrganizationMembershipRequest {
  def orgId: Id[Organization]
  def requesterId: Id[User]
  def targetId: Id[User]
}

@json
case class OrganizationMembershipAddRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole) extends OrganizationMembershipRequest

@json
case class OrganizationMembershipModifyRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole) extends OrganizationMembershipRequest

@json
case class OrganizationMembershipRemoveRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User]) extends OrganizationMembershipRequest

@json
case class OrganizationMembershipAddResponse(request: OrganizationMembershipAddRequest, membership: OrganizationMembership)
@json
case class OrganizationMembershipModifyResponse(request: OrganizationMembershipModifyRequest, membership: OrganizationMembership)
@json
case class OrganizationMembershipRemoveResponse(request: OrganizationMembershipRemoveRequest)

@json
case class OrganizationModifications(
  newName: Option[String] = None,
  newBasePermissions: Option[BasePermissions] = None)

sealed abstract class OrganizationRequest

@json
case class OrganizationCreateRequest(
  userId: Id[User],
  orgName: String) extends OrganizationRequest
@json
case class OrganizationCreateResponse(request: OrganizationCreateRequest, newOrg: Organization)

@json
case class OrganizationModifyRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  modifications: OrganizationModifications) extends OrganizationRequest
@json
case class OrganizationModifyResponse(request: OrganizationModifyRequest, modifiedOrg: Organization)

@json
case class OrganizationDeleteRequest(
  orgId: Id[Organization],
  requesterId: Id[User]) extends OrganizationRequest
@json
case class OrganizationDeleteResponse(request: OrganizationDeleteRequest)

sealed abstract class OrganizationFail(val status: Int, val message: String) {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}

//TODO: when modifying these, make sure we do not break existing Mobile Controllers that are calling .asErrorResponse. Preferably don't modify, just add as needed.
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(UNAUTHORIZED, "insufficient_permissions")
  case object HANDLE_UNAVAILABLE extends OrganizationFail(FORBIDDEN, "handle_unavailable")
  case object NOT_A_MEMBER extends OrganizationFail(UNAUTHORIZED, "not_a_member")
  case object NO_VALID_INVITATIONS extends OrganizationFail(FORBIDDEN, "no_valid_invitations")
  case object INVALID_PUBLIC_ID extends OrganizationFail(BAD_REQUEST, "invalid_public_id")

  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case HANDLE_UNAVAILABLE.message => HANDLE_UNAVAILABLE
      case NOT_A_MEMBER.message => NOT_A_MEMBER
      case NO_VALID_INVITATIONS.message => NO_VALID_INVITATIONS
      case INVALID_PUBLIC_ID.message => INVALID_PUBLIC_ID
    }
  }
}

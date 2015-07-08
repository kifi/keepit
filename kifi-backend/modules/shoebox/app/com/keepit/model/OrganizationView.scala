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

case class OrganizationView(
  orgId: Id[Organization],
  handle: OrganizationHandle,
  name: String,
  description: Option[String],
  avatarPath: Option[ImagePath],
  ownerId: Id[User],
  members: Seq[Id[User]],
  numMembers: Int,
  numLibraries: Int)

case class OrganizationCard(orgId: Id[Organization], handle: OrganizationHandle, name: String, description: Option[String], avatarPath: Option[ImagePath],
  numMembers: Int, numLibraries: Int)

object OrganizationNotificationInfo {
  def fromOrganization(org: Organization, image: Option[OrganizationAvatar])(implicit config: PublicIdConfiguration): OrganizationNotificationInfo = {
    OrganizationNotificationInfo(Organization.publicId(org.id.get), org.name, org.handle, image.map(OrganizationImageInfo.createInfo))
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
  name: Option[String] = None,
  description: Option[String] = None,
  basePermissions: Option[BasePermissions] = None)

sealed abstract class OrganizationRequest

case class OrganizationCreateRequest(
  requesterId: Id[User],
  initialValues: OrganizationModifications) extends OrganizationRequest
case class OrganizationCreateResponse(request: OrganizationCreateRequest, newOrg: Organization)

case class OrganizationModifyRequest(
  requesterId: Id[User],
  orgId: Id[Organization],
  modifications: OrganizationModifications) extends OrganizationRequest
case class OrganizationModifyResponse(request: OrganizationModifyRequest, modifiedOrg: Organization)

case class OrganizationDeleteRequest(
  requesterId: Id[User],
  orgId: Id[Organization]) extends OrganizationRequest
case class OrganizationDeleteResponse(request: OrganizationDeleteRequest)

case class OrganizationTransferRequest(
  requesterId: Id[User],
  orgId: Id[Organization],
  newOwner: Id[User]) extends OrganizationRequest
case class OrganizationTransferResponse(request: OrganizationTransferRequest, modifiedOrg: Organization)

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
  case object BAD_PARAMETERS extends OrganizationFail(BAD_REQUEST, "bad_parameters")

  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case HANDLE_UNAVAILABLE.message => HANDLE_UNAVAILABLE
      case NOT_A_MEMBER.message => NOT_A_MEMBER
      case NO_VALID_INVITATIONS.message => NO_VALID_INVITATIONS
      case INVALID_PUBLIC_ID.message => INVALID_PUBLIC_ID
      case BAD_PARAMETERS.message => BAD_PARAMETERS
    }
  }
}

case class OrganizationMembershipInfo(numTotalKeeps: Int, numTotalChats: Int)

case class AnalyticsOrganizationViewExtras(
  numTotalKeeps: Int,
  numTotalChats: Int,
  membersInfo: Map[Id[User], OrganizationMembershipInfo])

case class AnalyticsOrganizationView(orgView: OrganizationView, analyticsExtras: AnalyticsOrganizationViewExtras)

case class AnalyticsOrganizationCardExtras(numTotalKeeps: Int, numTotalChats: Int)
case class AnalyticsOrganizationCard(orgCard: OrganizationCard, analyticsExtras: AnalyticsOrganizationCardExtras)

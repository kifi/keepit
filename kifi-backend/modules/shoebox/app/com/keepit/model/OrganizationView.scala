package com.keepit.model

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.common.store.ImagePath
import com.kifi.macros.json
import play.api.http.Status._

@json case class OrganizationImageInfo(path: ImagePath, x: Int, y: Int)

object OrganizationImageInfo {
  def createInfo(img: OrganizationAvatar) = OrganizationImageInfo(img.imagePath, img.position.map(_.x).getOrElse(50), img.position.map(_.y).getOrElse(50))
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

case class OrganizationMemberInvitation(invitedBy: Either[Id[User], EmailAddress], role: OrganizationRole, msgOpt: Option[String] = None)

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

sealed abstract class OrganizationFail(val status: Int, val message: String)
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(FORBIDDEN, "insufficient_permissions")
  case object NOT_A_MEMBER extends OrganizationFail(UNAUTHORIZED, "not_a_member")
  case object NO_VALID_INVITATIONS extends OrganizationFail(FORBIDDEN, "no_valid_invitations")
  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
      case NOT_A_MEMBER.message => NOT_A_MEMBER
      case NO_VALID_INVITATIONS.message => NO_VALID_INVITATIONS
    }
  }
}

package com.keepit.model

import com.keepit.common.db.Id
import play.api.http.Status._

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

case class OrganizationMembershipAddResponse(request: OrganizationMembershipAddRequest)
case class OrganizationMembershipModifyResponse(request: OrganizationMembershipModifyRequest)
case class OrganizationMembershipRemoveResponse(request: OrganizationMembershipRemoveRequest)

sealed abstract class OrganizationFail(val status: Int, val message: String)
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(FORBIDDEN, "insufficient_permissions")
  case object NOT_A_MEMBER extends OrganizationFail(UNAUTHORIZED, "not_a_member")
  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
    }
  }
}

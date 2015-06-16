package com.keepit.model

import com.keepit.common.db.Id
import play.api.http.Status.FORBIDDEN

case class OrganizationMembershipModifyRequest(
  orgId: Id[Organization],
  requesterId: Id[User],
  targetId: Id[User],
  newRole: OrganizationRole)

case class OrganizationMembershipAddResponse(request: OrganizationMembershipModifyRequest)
case class OrganizationMembershipModifyResponse(request: OrganizationMembershipModifyRequest)
case class OrganizationMembershipRemoveResponse(request: OrganizationMembershipModifyRequest)

sealed abstract class OrganizationFail(val status: Int, val message: String)
object OrganizationFail {
  case object INSUFFICIENT_PERMISSIONS extends OrganizationFail(FORBIDDEN, "insufficient_permissions")

  def apply(str: String): OrganizationFail = {
    str match {
      case INSUFFICIENT_PERMISSIONS.message => INSUFFICIENT_PERMISSIONS
    }
  }
}

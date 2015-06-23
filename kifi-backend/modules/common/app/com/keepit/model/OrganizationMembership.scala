package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationMembership(
    id: Option[Id[OrganizationMembership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationMembership] = OrganizationMembershipStates.ACTIVE,
    seq: SequenceNumber[OrganizationMembership] = SequenceNumber.ZERO,
    organizationId: Id[Organization],
    userId: Id[User],
    role: OrganizationRole,
    permissions: Set[OrganizationPermission]) extends ModelWithState[OrganizationMembership] with ModelWithSeqNumber[OrganizationMembership] {

  def withId(id: Id[OrganizationMembership]): OrganizationMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembership = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembership]): OrganizationMembership = this.copy(state = newState)
  def withPermissions(newPermissions: Set[OrganizationPermission]): OrganizationMembership = this.copy(permissions = newPermissions)

  def hasPermission(p: OrganizationPermission): Boolean = permissions.contains(p)

  def isOwner: Boolean = role == OrganizationRole.OWNER
  def hasRole(r: OrganizationRole): Boolean = r == role
}

object OrganizationMembership {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationMembership]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationMembership]) and
    (__ \ 'seq).format(SequenceNumber.format[OrganizationMembership]) and
    (__ \ 'organizationId).format[Id[Organization]] and
    (__ \ 'userId).format[Id[User]] and
    (__ \ 'role).format[OrganizationRole] and
    (__ \ 'permissions).format[Set[OrganizationPermission]]
  )(OrganizationMembership.apply, unlift(OrganizationMembership.unapply))
}
object OrganizationMembershipStates extends States[OrganizationMembership]

sealed abstract class OrganizationPermission(val value: String)

object OrganizationPermission {
  case object VIEW_ORGANIZATION extends OrganizationPermission("view_organization")
  case object EDIT_ORGANIZATION extends OrganizationPermission("edit_organization")
  case object INVITE_MEMBERS extends OrganizationPermission("invite_members")
  case object REMOVE_MEMBERS extends OrganizationPermission("remove_members")
  case object ADD_LIBRARIES extends OrganizationPermission("add_libraries")
  case object REMOVE_LIBRARIES extends OrganizationPermission("remove_libraries")

  def all: Set[OrganizationPermission] = Set(VIEW_ORGANIZATION, EDIT_ORGANIZATION, INVITE_MEMBERS, REMOVE_MEMBERS, ADD_LIBRARIES, REMOVE_LIBRARIES)

  implicit val format: Format[OrganizationPermission] =
    Format(__.read[String].map(OrganizationPermission(_)), new Writes[OrganizationPermission] {
      def writes(o: OrganizationPermission) = JsString(o.value)
    })

  def apply(str: String): OrganizationPermission = {
    str match {
      case VIEW_ORGANIZATION.value => VIEW_ORGANIZATION
      case EDIT_ORGANIZATION.value => EDIT_ORGANIZATION
      case INVITE_MEMBERS.value => INVITE_MEMBERS
      case REMOVE_MEMBERS.value => REMOVE_MEMBERS
      case ADD_LIBRARIES.value => ADD_LIBRARIES
      case REMOVE_LIBRARIES.value => REMOVE_LIBRARIES
    }
  }

}

sealed abstract class OrganizationRole(val value: String, val priority: Int) extends Ordered[OrganizationRole] {
  // reverse compare to ensure that 0 is highest priority
  override def compare(that: OrganizationRole): Int = that.priority compare priority
}

object OrganizationRole {
  case object OWNER extends OrganizationRole("owner", 0)
  case object MEMBER extends OrganizationRole("member", 1)

  implicit val format: Format[OrganizationRole] =
    Format(__.read[String].map(OrganizationRole(_)), new Writes[OrganizationRole] {
      def writes(o: OrganizationRole) = JsString(o.value)
    })

  def apply(str: String): OrganizationRole = {
    str match {
      case OWNER.value => OWNER
      case MEMBER.value => MEMBER
    }
  }

  def all: Seq[OrganizationRole] = Seq(OWNER, MEMBER)
}

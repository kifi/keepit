package com.keepit.model

import com.keepit.common.cache.{ JsonCacheImpl, FortyTwoCachePlugin, CacheStatistics, Key }
import com.keepit.common.db._
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.duration.Duration

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
  def sanitizeForDelete: OrganizationMembership = this.copy(
    state = OrganizationMembershipStates.INACTIVE
  )

  def isActive = state == OrganizationMembershipStates.ACTIVE

  def hasPermission(p: OrganizationPermission): Boolean = permissions.contains(p)

  def toIngestableOrganizationMembership = IngestableOrganizationMembership(id.get, organizationId, userId, createdAt, state, seq)
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
  case object MODIFY_MEMBERS extends OrganizationPermission("modify_members")
  case object REMOVE_MEMBERS extends OrganizationPermission("remove_members")
  case object ADD_LIBRARIES extends OrganizationPermission("add_libraries")
  case object REMOVE_LIBRARIES extends OrganizationPermission("remove_libraries")

  def all: Set[OrganizationPermission] = Set(VIEW_ORGANIZATION, EDIT_ORGANIZATION, INVITE_MEMBERS, MODIFY_MEMBERS, REMOVE_MEMBERS, ADD_LIBRARIES, REMOVE_LIBRARIES)

  implicit val format: Format[OrganizationPermission] =
    Format(__.read[String].map(OrganizationPermission(_)), new Writes[OrganizationPermission] {
      def writes(o: OrganizationPermission) = JsString(o.value)
    })

  def apply(str: String): OrganizationPermission = {
    str match {
      case VIEW_ORGANIZATION.value => VIEW_ORGANIZATION
      case EDIT_ORGANIZATION.value => EDIT_ORGANIZATION
      case INVITE_MEMBERS.value => INVITE_MEMBERS
      case MODIFY_MEMBERS.value => MODIFY_MEMBERS
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
  case object ADMIN extends OrganizationRole("admin", 0)
  case object MEMBER extends OrganizationRole("member", 1)

  implicit val format: Format[OrganizationRole] =
    Format(__.read[String].map(OrganizationRole(_)), new Writes[OrganizationRole] {
      def writes(o: OrganizationRole) = JsString(o.value)
    })

  def apply(str: String): OrganizationRole = {
    str match {
      case ADMIN.value => ADMIN
      case MEMBER.value => MEMBER
    }
  }

  def all: Set[OrganizationRole] = Set(ADMIN, MEMBER)
  def allOpts: Set[Option[OrganizationRole]] = all.map(Some(_)) ++ Set(None)
}

case class IngestableOrganizationMembership(
  id: Id[OrganizationMembership],
  orgId: Id[Organization],
  userId: Id[User],
  createdAt: DateTime,
  state: State[OrganizationMembership],
  seq: SequenceNumber[OrganizationMembership])

object IngestableOrganizationMembership {
  implicit val format = Json.format[IngestableOrganizationMembership]
}

case class OrganizationMembersKey(id: Id[Organization]) extends Key[Set[Id[User]]] {
  override val version = 4
  val namespace = "user_ids_by_organization"
  def toKey(): String = id.id.toString
}

class OrganizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationMembersKey, Set[Id[User]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)


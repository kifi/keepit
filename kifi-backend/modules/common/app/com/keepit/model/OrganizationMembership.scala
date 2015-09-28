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
    role: OrganizationRole) extends ModelWithState[OrganizationMembership] with ModelWithSeqNumber[OrganizationMembership] {

  def withId(id: Id[OrganizationMembership]): OrganizationMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembership = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembership]): OrganizationMembership = this.copy(state = newState)
  def sanitizeForDelete: OrganizationMembership = this.copy(
    state = OrganizationMembershipStates.INACTIVE
  )

  def isActive = state == OrganizationMembershipStates.ACTIVE
  def isInactive = state == OrganizationMembershipStates.INACTIVE

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
    (__ \ 'role).format[OrganizationRole]
  )(OrganizationMembership.apply, unlift(OrganizationMembership.unapply))
}
object OrganizationMembershipStates extends States[OrganizationMembership]

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

@json
case class OrganizationUserRelationship(
  orgId: Id[Organization],
  userId: Id[User],
  role: Option[OrganizationRole],
  permissions: Option[Set[OrganizationPermission]],
  isInvited: Boolean,
  isCandidate: Boolean)

case class OrganizationMembersKey(id: Id[Organization]) extends Key[Set[Id[User]]] {
  override val version = 1
  val namespace = "member_ids_by_organization"
  def toKey(): String = id.id.toString
}

class OrganizationMembersCache(stats: CacheStatistics, accessLog: AccessLog, innermostPluginSettings: (FortyTwoCachePlugin, Duration), innerToOuterPluginSettings: (FortyTwoCachePlugin, Duration)*)
  extends JsonCacheImpl[OrganizationMembersKey, Set[Id[User]]](stats, accessLog, innermostPluginSettings, innerToOuterPluginSettings: _*)


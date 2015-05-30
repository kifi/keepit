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
    access: OrganizationAccess) extends ModelWithState[OrganizationMembership] with ModelWithSeqNumber[OrganizationMembership] {

  def withId(id: Id[OrganizationMembership]): OrganizationMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembership = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembership]): OrganizationMembership = this.copy(state = newState)

  override def toString: String = s"OrganizationMembership[id=$id,organizationId=$organizationId,userId=$userId,access=$access,state=$state]"

  def canInsert: Boolean = access == OrganizationAccess.OWNER || access == OrganizationAccess.READ_WRITE || access == OrganizationAccess.READ_INSERT
  def canWrite: Boolean = access == OrganizationAccess.OWNER || access == OrganizationAccess.READ_WRITE
  def isOwner: Boolean = access == OrganizationAccess.OWNER
  def isCollaborator: Boolean = access == OrganizationAccess.READ_WRITE
  def isFollower: Boolean = access == OrganizationAccess.READ_ONLY
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
    (__ \ 'access).format[OrganizationAccess]
  )(OrganizationMembership.apply, unlift(OrganizationMembership.unapply))

  def applyFromDbRow(
    id: Option[Id[OrganizationMembership]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[OrganizationMembership],
    seq: SequenceNumber[OrganizationMembership],
    organizationId: Id[Organization],
    userId: Id[User],
    access: OrganizationAccess) = {
    OrganizationMembership(id, createdAt, updatedAt, state, seq, organizationId, userId, access)
  }

  def unapplyToDbRow(member: OrganizationMembership) = {
    Some(
      member.id,
      member.createdAt,
      member.updatedAt,
      member.state,
      member.seq,
      member.organizationId,
      member.userId,
      member.access)
  }
}

object OrganizationMembershipStates extends States[OrganizationMembership]

sealed abstract class OrganizationAccess(val value: String, val priority: Int) {
  def isHigherAccess(x: OrganizationAccess): Boolean = {
    this.priority > x.priority
  }

  def isHigherOrEqualAccess(x: OrganizationAccess): Boolean = {
    this.priority >= x.priority
  }

  def isLowerAccess(x: OrganizationAccess): Boolean = {
    this.priority < x.priority
  }

  def isLowerOrEqualAccess(x: OrganizationAccess): Boolean = {
    this.priority <= x.priority
  }
}

object OrganizationAccess {
  case object READ_ONLY extends OrganizationAccess("read_only", 0)
  case object READ_INSERT extends OrganizationAccess("read_insert", 1)
  case object READ_WRITE extends OrganizationAccess("read_write", 2)
  case object OWNER extends OrganizationAccess("owner", 3)

  implicit def format[T]: Format[OrganizationAccess] =
    Format(__.read[String].map(OrganizationAccess(_)), new Writes[OrganizationAccess] {
      def writes(o: OrganizationAccess) = JsString(o.value)
    })

  implicit def ord: Ordering[OrganizationAccess] = new Ordering[OrganizationAccess] {
    def compare(x: OrganizationAccess, y: OrganizationAccess): Int = x.priority compare y.priority
  }

  def apply(str: String): OrganizationAccess = {
    str match {
      case READ_ONLY.value => READ_ONLY
      case READ_INSERT.value => READ_INSERT
      case READ_WRITE.value => READ_WRITE
      case OWNER.value => OWNER
    }
  }

  def all: Seq[OrganizationAccess] = Seq(OWNER, READ_WRITE, READ_INSERT, READ_ONLY)

  def collaborativePermissions: Set[OrganizationAccess] = Set(OWNER, READ_WRITE, READ_INSERT)
}

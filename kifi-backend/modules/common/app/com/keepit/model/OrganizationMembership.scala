package com.keepit.model

import com.keepit.common.crypto.ModelWithPublicId
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

  def canWrite: Boolean = access == OrganizationAccess.OWNER || access == OrganizationAccess.READ_WRITE
  def isOwner: Boolean = access == OrganizationAccess.OWNER
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
}

object OrganizationMembershipStates extends States[OrganizationMembership]

sealed abstract class OrganizationAccess(val value: String, val priority: Int)

object OrganizationAccess {
  case object READ_WRITE extends OrganizationAccess("read_write", 0)
  case object OWNER extends OrganizationAccess("owner", 1)

  implicit def format[T]: Format[OrganizationAccess] =
    Format(__.read[String].map(OrganizationAccess(_)), new Writes[OrganizationAccess] {
      def writes(o: OrganizationAccess) = JsString(o.value)
    })

  implicit def ord: Ordering[OrganizationAccess] = new Ordering[OrganizationAccess] {
    def compare(x: OrganizationAccess, y: OrganizationAccess): Int = x.priority compare y.priority
  }

  def apply(str: String): OrganizationAccess = {
    str match {
      case READ_WRITE.value => READ_WRITE
      case OWNER.value => OWNER
    }
  }

  def all: Seq[OrganizationAccess] = Seq(OWNER, READ_WRITE)
}

package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationMembership(
                              id: Option[Id[OrganizationMembership]] = None,
                              organizationId: Id[Organization],
                              userId: Id[User],
                              access: OrganizationAccess,
                              createdAt: DateTime = currentDateTime,
                              updatedAt: DateTime = currentDateTime,
                              state: State[OrganizationMembership] = OrganizationMembershipStates.ACTIVE,
                              seq: SequenceNumber[OrganizationMembership] = SequenceNumber.ZERO,
                              showInSearch: Boolean = true, // doesn't make sense for organization unless you are searching what a user is part of (libs / orgs).
                              listedOnProfile: Boolean = true, // whether organization appears on user's profile
                              lastViewed: Option[DateTime] = None,
                              lastEmailSent: Option[DateTime] = None,
                              lastJoinedAt: Option[DateTime] = None,
                              subscribedToUpdates: Boolean = false) extends ModelWithState[OrganizationMembership] with ModelWithSeqNumber[OrganizationMembership] {

  def withId(id: Id[OrganizationMembership]): OrganizationMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembership = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembership]): OrganizationMembership = this.copy(state = newState)

  override def toString: String = s"OrganizationMembership[id=$id,libraryId=$organizationId,userId=$userId,access=$access,state=$state]"

  def canInsert: Boolean = access == OrganizationAccess.OWNER || access == OrganizationAccess.READ_WRITE || access == OrganizationAccess.READ_INSERT
  def canWrite: Boolean = access == OrganizationAccess.OWNER || access == OrganizationAccess.READ_WRITE
  def isOwner: Boolean = access == OrganizationAccess.OWNER
  def isCollaborator: Boolean = access == OrganizationAccess.READ_WRITE
  def isFollower: Boolean = access == OrganizationAccess.READ_ONLY
}

object OrganizationMembership {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationMembership]) and
      (__ \ 'libraryId).format[Id[Organization]] and
      (__ \ 'userId).format[Id[User]] and
      (__ \ 'access).format[OrganizationAccess] and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'state).format(State.format[OrganizationMembership]) and
      (__ \ 'seq).format(SequenceNumber.format[OrganizationMembership]) and
      (__ \ 'showInSearch).format[Boolean] and
      (__ \ 'listedOnProfile).format[Boolean] and
      (__ \ 'lastViewed).formatNullable[DateTime] and
      (__ \ 'lastEmailSent).formatNullable[DateTime] and
      (__ \ 'lastJoinedAt).formatNullable[DateTime] and
      (__ \ 'subscribedToUpdates).format[Boolean]
    )(OrganizationMembership.apply, unlift(OrganizationMembership.unapply))
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
    Format(__.read[String].map(OrganizationAccess(_)), new Writes[OrganizationAccess] { def writes(o: OrganizationAccess) = JsString(o.value) })

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

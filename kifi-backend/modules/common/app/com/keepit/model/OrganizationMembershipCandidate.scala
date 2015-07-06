package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class OrganizationMembershipCandidate(
    id: Option[Id[OrganizationMembershipCandidate]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[OrganizationMembershipCandidate] = OrganizationMembershipCandidateStates.ACTIVE,
    orgId: Id[Organization],
    userId: Id[User]) extends ModelWithState[OrganizationMembershipCandidate] {

  def withId(id: Id[OrganizationMembershipCandidate]): OrganizationMembershipCandidate = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): OrganizationMembershipCandidate = this.copy(updatedAt = now)
  def withState(newState: State[OrganizationMembershipCandidate]): OrganizationMembershipCandidate = this.copy(state = newState)

  def sanitizeForDelete: OrganizationMembershipCandidate = this.copy(state = OrganizationMembershipCandidateStates.INACTIVE)
}

object OrganizationMembershipCandidateStates extends States[OrganizationMembershipCandidate] {
  def all = Set(ACTIVE, INACTIVE)
}

object OrganizationMembershipCandidate {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[OrganizationMembershipCandidate]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[OrganizationMembershipCandidate]) and
    (__ \ 'orgId).format[Id[Organization]] and
    (__ \ 'userId).format[Id[User]]
  )(OrganizationMembershipCandidate.apply, unlift(OrganizationMembershipCandidate.unapply))
}

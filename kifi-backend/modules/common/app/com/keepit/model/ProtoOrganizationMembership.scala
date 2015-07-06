package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ProtoOrganizationMembership(
    id: Option[Id[ProtoOrganizationMembership]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ProtoOrganizationMembership] = ProtoOrganizationMembershipStates.ACTIVE,
    protoOrgId: Id[ProtoOrganization],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None) extends ModelWithState[ProtoOrganizationMembership] {

  def withId(id: Id[ProtoOrganizationMembership]): ProtoOrganizationMembership = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ProtoOrganizationMembership = this.copy(updatedAt = now)
  def withState(newState: State[ProtoOrganizationMembership]): ProtoOrganizationMembership = this.copy(state = newState)

  def sanitizeForDelete: ProtoOrganizationMembership = this.copy(state = ProtoOrganizationMembershipStates.INACTIVE)
}

object ProtoOrganizationMembershipStates extends States[ProtoOrganizationMembership] {
  def all = Set(ACTIVE, INACTIVE)
}

object ProtoOrganizationMembership {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[ProtoOrganizationMembership]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[ProtoOrganizationMembership]) and
    (__ \ 'protoOrgId).format[Id[ProtoOrganization]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]]
  )(ProtoOrganizationMembership.apply, unlift(ProtoOrganizationMembership.unapply))
}

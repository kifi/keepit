package com.keepit.model

import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ProtoOrganizationInvite(
    id: Option[Id[ProtoOrganizationInvite]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[ProtoOrganizationInvite] = ProtoOrganizationInviteStates.ACTIVE,
    protoOrgId: Id[ProtoOrganization],
    inviterId: Id[User],
    userId: Option[Id[User]] = None,
    emailAddress: Option[EmailAddress] = None) extends ModelWithState[ProtoOrganizationInvite] {

  def withId(id: Id[ProtoOrganizationInvite]): ProtoOrganizationInvite = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): ProtoOrganizationInvite = this.copy(updatedAt = now)
  def withState(newState: State[ProtoOrganizationInvite]): ProtoOrganizationInvite = this.copy(state = newState)

  def sanitizeForDelete: ProtoOrganizationInvite = this.copy(state = ProtoOrganizationInviteStates.INACTIVE)
}

object ProtoOrganizationInviteStates extends States[ProtoOrganizationInvite]

object ProtoOrganizationInvite {
  implicit def format = (
    (__ \ 'id).formatNullable(Id.format[ProtoOrganizationInvite]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[ProtoOrganizationInvite]) and
    (__ \ 'protoOrgId).format[Id[ProtoOrganization]] and
    (__ \ 'inviterId).format[Id[User]] and
    (__ \ 'userId).format[Option[Id[User]]] and
    (__ \ 'emailAddress).format[Option[EmailAddress]]
  )(ProtoOrganizationInvite.apply, unlift(ProtoOrganizationInvite.unapply))
}

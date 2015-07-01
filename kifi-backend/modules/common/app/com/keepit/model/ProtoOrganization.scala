package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import org.joda.time.DateTime
import com.keepit.common.time._
import play.api.libs.functional.syntax._
import play.api.libs.json._

case class ProtoOrganization(
  id: Option[Id[ProtoOrganization]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[ProtoOrganization] = ProtoOrganizationStates.ACTIVE,
  seq: SequenceNumber[ProtoOrganization] = SequenceNumber.ZERO,
  name: String,
  description: Option[String] = None,
  ownerId: Id[User],
  members: Seq[Id[User]],
  inviteeEmails: Seq[EmailAddress]) extends ModelWithState[ProtoOrganization] with ModelWithSeqNumber[ProtoOrganization] {

  def withId(id: Id[ProtoOrganization]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object ProtoOrganizationStates extends States[ProtoOrganization] {}

object ProtoOrganization {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ProtoOrganization]) and
      (__ \ 'createdAt).format(DateTimeJsonFormat) and
      (__ \ 'updatedAt).format(DateTimeJsonFormat) and
      (__ \ 'state).format(State.format[ProtoOrganization]) and
      (__ \ 'seq).format(SequenceNumber.format[ProtoOrganization]) and
      (__ \ 'name).format[String] and
      (__ \ 'description).formatNullable[String] and
      (__ \ 'ownerId).format[Id[User]] and
      (__ \ 'members).format[Seq[Id[User]]] and
      (__ \ 'inviteeEmails).format[Seq[EmailAddress]]
    )(ProtoOrganization.apply, unlift(ProtoOrganization.unapply))
}

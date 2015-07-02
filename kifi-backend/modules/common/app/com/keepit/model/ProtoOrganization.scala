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
    name: String,
    description: Option[String] = None,
    ownerId: Id[User]) extends ModelWithState[ProtoOrganization] {

  def withId(id: Id[ProtoOrganization]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
  def withState(newState: State[ProtoOrganization]) = this.copy(state = newState)
}

object ProtoOrganizationStates extends States[ProtoOrganization] {}

object ProtoOrganization {
  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ProtoOrganization]) and
    (__ \ 'createdAt).format(DateTimeJsonFormat) and
    (__ \ 'updatedAt).format(DateTimeJsonFormat) and
    (__ \ 'state).format(State.format[ProtoOrganization]) and
    (__ \ 'name).format[String] and
    (__ \ 'description).formatNullable[String] and
    (__ \ 'ownerId).format[Id[User]]
  )(ProtoOrganization.apply, unlift(ProtoOrganization.unapply))
}

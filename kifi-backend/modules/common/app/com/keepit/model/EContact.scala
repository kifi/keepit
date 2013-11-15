package com.keepit.model

import com.keepit.common.time._
import com.keepit.common.db._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

object EContactStates extends States[EContact] {
  val PARSE_FAILURE = State[EContact]("parse_failure")
}

case class EContact(
  id: Option[Id[EContact]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId:    Id[User],
  email:     String,
  name:      Option[String] = None,
  firstName: Option[String] = None,
  lastName:  Option[String] = None,
  state:     State[EContact] = EContactStates.ACTIVE
) extends Model[EContact] {
  def withId(id: Id[EContact]) = this.copy(id = Some(id))
  def withName(name: Option[String]) = this.copy(name = name)
  def withEmail(email: String) = this.copy(email = email)
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object EContact {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[EContact]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'email).format[String] and
      (__ \ 'name).formatNullable[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String] and
      (__ \ 'state).format(State.format[EContact])
    )(EContact.apply, unlift(EContact.unapply))
}

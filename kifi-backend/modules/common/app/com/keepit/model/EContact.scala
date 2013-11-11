package com.keepit.model

import com.keepit.common.time._
import com.keepit.common.db._
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class EContact(
  id: Option[Id[EContact]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId:    Id[User],
  email:     String,
  name:      String,
  firstName: Option[String] = None,
  lastName:  Option[String] = None
) extends Model[EContact] {
  def withId(id: Id[EContact]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object EContact {
  implicit val format = (
      (__ \ 'id).formatNullable(Id.format[EContact]) and
      (__ \ 'createdAt).format[DateTime] and
      (__ \ 'updatedAt).format[DateTime] and
      (__ \ 'userId).format(Id.format[User]) and
      (__ \ 'email).format[String] and
      (__ \ 'name).format[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String]
    )(EContact.apply, unlift(EContact.unapply))
}

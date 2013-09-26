package com.keepit.model

import com.keepit.common.db._
import com.keepit.common.time._
import org.joda.time.DateTime

case class ContactInfo (
  id: Option[Id[ContactInfo]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  userId: Id[User],
  email: String,
  origin: Option[String] = None,
  name: Option[String] = None,
  firstName: Option[String] = None,
  lastName: Option[String] = None,
  pictureUrl: Option[String] = None,
  parentId: Option[Id[ContactInfo]] = None
) extends Model[ContactInfo] {
  def withId(id: Id[ContactInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object ContactInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  import com.keepit.common.db.Id

  implicit val format = (
    (__ \ 'id).formatNullable(Id.format[ContactInfo]) and
    (__ \ 'createdAt).format[DateTime] and
    (__ \ 'updatedAt).format[DateTime] and
    (__ \ 'userId).format(Id.format[User]) and
    (__ \ 'email).format[String] and
    (__ \ 'origin).formatNullable[String] and
    (__ \ 'name).formatNullable[String] and
    (__ \ 'firstName).formatNullable[String] and
    (__ \ 'lastName).formatNullable[String] and
    (__ \ 'pictureUrl).formatNullable[String] and
    (__ \ 'parentId).formatNullable(Id.format[ContactInfo])
  )(ContactInfo.apply, unlift(ContactInfo.unapply))
}
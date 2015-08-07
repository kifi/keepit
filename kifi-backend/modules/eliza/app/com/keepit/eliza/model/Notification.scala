package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NKind, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

case class Notification(
    id: Option[Id[Notification]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    userId: Id[User],
    lastChecked: DateTime = START_OF_TIME,
    kind: NKind) extends Model[Notification] {

  override def withId(id: Id[Notification]): Notification = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Notification = copy(updatedAt = updatedAt)

}

object Notification {

  implicit val format = (
    (__ \ "id").formatNullable[Id[Notification]] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "updatedAt").format[DateTime] and
    (__ \ "userId").format[Id[User]] and
    (__ \ "lastChecked").format[DateTime] and
    (__ \ "kind").format[String]
  )(Notification.applyFromDbRow, unlift(Notification.unapplyToDbRow))

  def applyFromDbRow(
    id: Option[Id[Notification]],
    createdAt: DateTime,
    updatedAt: DateTime,
    userId: Id[User],
    lastChecked: DateTime,
    kind: String): Notification = Notification(
    id,
    createdAt,
    updatedAt,
    userId,
    lastChecked,
    NotificationKind.getByName(kind).get
  )

  def unapplyToDbRow(notification: Notification): Option[(Option[Id[Notification]], DateTime, DateTime, Id[User], DateTime, String)] = Some(
    notification.id,
    notification.createdAt,
    notification.updatedAt,
    notification.userId,
    notification.lastChecked,
    notification.kind.name
  )

}

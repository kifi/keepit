package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NKind, NotificationKind }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
 * The architecture of delivering and handling what is conceptually a "notification to the user" is split between
 * several classes.
 *
 * [[com.keepit.notify.model.NotificationEvent]] represents something that happened in the Kifi application. At this point
 * we're not sure anything will even be delivered, but we'll hand it over to Eliza for processing. Each event has a distinct
 * [[NotificationKind]] describing its grouping behavior.
 *
 * [[NotificationItem]] is essentially a persisted [[com.keepit.notify.model.NotificationEvent]] that is attached to a notification.
 * When Eliza receives a new [[com.keepit.notify.model.NotificationEvent]], it looks at previous similar events (specifically, those
 * to the same user and of the same kind) and decides whether or not to group the new event with previous events.
 *
 * [[Notification]] represents a group of [[NotificationItem]]s and is what is finally displayed to the user as a clickable, tappable,
 * or otherwise actionable UI element. When new events flow in, it is possible for a notification to be updated with new [[NotificationItem]]s,
 * or a new one to be created.
 */
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

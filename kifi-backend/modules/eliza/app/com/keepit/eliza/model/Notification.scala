package com.keepit.eliza.model

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.info.NotificationInfo
import com.keepit.notify.model.event.{ NewMessage, NotificationEvent }
import com.keepit.notify.model._
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

/**
 * The architecture of delivering and handling what is conceptually a "notification to the user" is split between
 * several classes.
 *
 * [[NotificationEvent]] represents something that happened in the Kifi application. At this point
 * we're not sure anything will even be delivered, but we'll hand it over to Eliza for processing. Each event has a distinct
 * [[NotificationKind]] describing its grouping behavior.
 *
 * [[NotificationItem]] is essentially a persisted [[event.NotificationEvent]] that is attached to a notification.
 * When Eliza receives a new [[event.NotificationEvent]], it looks at previous similar events (specifically, those
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
    recipient: Recipient,
    lastChecked: Option[DateTime] = None,
    kind: NKind,
    groupIdentifier: Option[String],
    lastEvent: DateTime,
    disabled: Boolean = false,
    externalId: ExternalId[Notification] = ExternalId(),
    backfilledFor: Option[Id[UserThread]] = None) extends ModelWithExternalId[Notification] {

  def hasNewEvent: Boolean = lastChecked.fold(true) { checked =>
    lastEvent > checked
  }

  def unread: Boolean = !disabled && hasNewEvent

  def withUnread(unread: Boolean) = if (unread) copy(lastChecked = None) else copy(lastChecked = Some(lastEvent))

  def withDisabled(disabled: Boolean) = copy(disabled = disabled)

  override def withId(id: Id[Notification]): Notification = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): Notification = copy(updatedAt = updatedAt)

}

/**
 * Represents a notification and its collection of items, along with the guarantees that these objects hold.
 */
class ExtendedNotification(val notification: Notification, val items: Set[NotificationItem]) {

  //  require(items.forall(_.kind == notification.kind), s"Same-kind requirement failed for items $items and notif $notification")
  //  require(relevantItem.eventTime == notification.lastEvent, s"Most-recent-time requirement failed for items $items and notif $notification")

  def relevantItem = items.maxBy(_.eventTime)

  def unreadMessages: Set[NotificationItem] = items.filter(_.eventTime > notification.lastEvent)

  def unreadAuthors: Set[Recipient] = {
    // only makes sense if this is a message notification
    if (notification.kind == NewMessage) {
      items.map(_.event).collect {
        case e: NewMessage => e
      }.map(_.from)
    } else Set()
  }

}

case class NotificationWithItems(
  override val notification: Notification,
  override val items: Set[NotificationItem]) extends ExtendedNotification(notification, items)

object NotificationWithItems {
  implicit val format = Json.format[NotificationWithItems]
}

case class NotificationWithInfo(
  override val notification: Notification,
  override val items: Set[NotificationItem], info: NotificationInfo)
    extends ExtendedNotification(notification, items)

case class NotificationWithJson(
  override val notification: Notification,
  override val items: Set[NotificationItem],
  json: JsObject) extends ExtendedNotification(notification, items)

object Notification {

  implicit val format = (
    (__ \ "id").formatNullable[Id[Notification]] and
    (__ \ "createdAt").format[DateTime] and
    (__ \ "updatedAt").format[DateTime] and
    (__ \ "lastChecked").formatNullable[DateTime] and
    (__ \ "kind").format[String] and
    (__ \ "groupIdentifier").formatNullable[String] and
    (__ \ "recipient").format[Recipient] and
    (__ \ "lastEvent").format[DateTime] and
    (__ \ "disabled").format[Boolean] and
    (__ \ "externalId").format[ExternalId[Notification]] and
    (__ \ "backfilledFor").formatNullable[Id[UserThread]]
  )(Notification.applyFromDbRow, unlift(Notification.unapplyToDbRow))

  def applyFromDbRow(
    id: Option[Id[Notification]],
    createdAt: DateTime,
    updatedAt: DateTime,
    lastChecked: Option[DateTime],
    kind: String,
    groupIdentifier: Option[String],
    recipient: Recipient,
    lastEvent: DateTime,
    disabled: Boolean,
    externalId: ExternalId[Notification],
    backfilledFor: Option[Id[UserThread]]): Notification = Notification(
    id,
    createdAt,
    updatedAt,
    recipient,
    lastChecked,
    NotificationKind.getByName(kind).get,
    groupIdentifier,
    lastEvent,
    disabled,
    externalId,
    backfilledFor
  )

  def unapplyToDbRow(notification: Notification): Option[(Option[Id[Notification]], DateTime, DateTime, Option[DateTime], String, Option[String], Recipient, DateTime, Boolean, ExternalId[Notification], Option[Id[UserThread]])] = Some(
    notification.id,
    notification.createdAt,
    notification.updatedAt,
    notification.lastChecked,
    notification.kind.name,
    notification.groupIdentifier,
    notification.recipient,
    notification.lastEvent,
    notification.disabled,
    notification.externalId,
    notification.backfilledFor
  )

}

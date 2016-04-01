package com.keepit.eliza.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.notify.model.event.LegacyNotification
import com.keepit.notify.model.{ Recipient, NKind }
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationRepoImpl])
trait NotificationRepo extends Repo[Notification] with ExternalIdColumnFunction[Notification] {
  def getLastByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Option[Notification]
  def getMostRecentByGroupIdentifier(recipient: Recipient, kind: NKind, identifier: String)(implicit session: RSession): Option[Notification]
  def getNotificationsWithNewEventsCount(recipient: Recipient)(implicit session: RSession): Int
  def getUnreadNotificationsCount(recipient: Recipient)(implicit session: RSession): Int
  def getUnreadNotificationsCountForKind(recipient: Recipient, kind: String)(implicit session: RSession): Int
  def getUnreadNotificationsCountExceptKind(recipient: Recipient, kind: String)(implicit session: RSession): Int
  def setAllReadBefore(recipient: Recipient, time: DateTime)(implicit session: RWSession): Unit
  def setAllRead(recipient: Recipient)(implicit session: RWSession): Unit
  def getNotificationsWithNewEvents(recipient: Recipient, howMany: Int)(implicit session: RSession): Seq[Notification]
  def getNotificationsWithNewEventsBefore(recipient: Recipient, time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification]
  def getLatestNotifications(recipient: Recipient, howMany: Int)(implicit session: RSession): Seq[Notification]
  def getLatestNotificationsBefore(recipient: Recipient, time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification]

  // Note: this method is sad and I am sad having written it. Please do not use it.
  def getAllUnreadByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Seq[Notification]
}

@Singleton
class NotificationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends NotificationRepo with DbRepo[Notification] with ExternalIdColumnDbFunction[Notification] {

  import db.Driver.simple._

  type RepoImpl = NotificationTable
  class NotificationTable(tag: Tag) extends RepoTable[Notification](db, tag, "notification") with ExternalIdColumn[Notification] {

    def recipient = column[Recipient]("recipient", O.NotNull)
    def lastChecked = column[Option[DateTime]]("last_checked", O.Nullable)
    def kind = column[String]("kind", O.NotNull)
    def groupIdentifier = column[Option[String]]("group_identifier", O.Nullable)
    def lastEvent = column[DateTime]("last_event", O.NotNull)
    def disabled = column[Boolean]("disabled", O.NotNull)
    def backfilledFor = column[Option[Id[UserThread]]]("backfilled_for", O.Nullable)

    def hasNewEvent: Column[Boolean] = lastChecked.getOrElse(START_OF_TIME) < lastEvent
    def unread: Column[Boolean] = !disabled && hasNewEvent

    def ofKind(kind: NKind): Column[Boolean] = this.kind === kind.name

    def * = (id.?, createdAt, updatedAt, state, lastChecked, kind, groupIdentifier, recipient, lastEvent, disabled, externalId, backfilledFor) <> ((Notification.applyFromDbRow _).tupled, Notification.unapplyToDbRow)

  }

  def table(tag: Tag): NotificationTable = new NotificationTable(tag)
  initTable()

  private def activeRows = rows.filter(_.state === NotificationStates.ACTIVE)

  def deleteCache(model: Notification)(implicit session: RSession): Unit = {}
  def invalidateCache(model: Notification)(implicit session: RSession): Unit = {}

  def getLastByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    val query = (for (row <- activeRows if row.recipient === recipient && row.kind === kindStr) yield row).sortBy(_.lastEvent.desc)
    query.firstOption
  }

  def getMostRecentByGroupIdentifier(recipient: Recipient, kind: NKind, identifier: String)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    activeRows
      .filter(row => row.recipient === recipient && row.kind === kindStr && row.groupIdentifier === identifier)
      .sortBy(_.lastEvent desc)
      .firstOption
  }

  def getNotificationsWithNewEventsCount(recipient: Recipient)(implicit session: RSession): Int = {
    val unread = for (
      row <- activeRows if row.recipient === recipient && row.hasNewEvent
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def getUnreadNotificationsCount(recipient: Recipient)(implicit session: RSession): Int = {
    val unread = for (
      row <- activeRows if row.recipient === recipient && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def getUnreadNotificationsCountForKind(recipient: Recipient, kind: String)(implicit session: RSession): Int = {
    val unread = for (
      row <- activeRows if row.recipient === recipient && row.kind === kind && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def getUnreadNotificationsCountExceptKind(recipient: Recipient, kind: String)(implicit session: RSession): Int = {
    val unread = for (
      row <- activeRows if row.recipient === recipient && row.kind =!= kind && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def setAllReadBefore(recipient: Recipient, time: DateTime)(implicit session: RWSession): Unit = {
    val q = for (
      row <- activeRows if row.recipient === recipient && row.hasNewEvent && row.lastEvent <= time
    ) yield row.lastChecked
    q.update(Some(clock.now()))
  }

  def setAllRead(recipient: Recipient)(implicit session: RWSession): Unit = {
    val q = for (
      row <- activeRows if row.recipient === recipient && row.hasNewEvent
    ) yield row.lastChecked
    q.update(Some(clock.now()))
  }

  def getNotificationsWithNewEvents(recipient: Recipient, howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- activeRows if notif.recipient === recipient && notif.hasNewEvent
    } yield notif
    q.sortBy(_.lastEvent.desc).take(howMany).list
  }

  def getNotificationsWithNewEventsBefore(recipient: Recipient, time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- activeRows if notif.recipient === recipient && notif.lastEvent < time && notif.hasNewEvent
    } yield notif
    q.sortBy(_.lastEvent.desc).take(howMany).list
  }

  def getLatestNotifications(recipient: Recipient, howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- activeRows if notif.recipient === recipient
    } yield notif
    q.sortBy(_.lastEvent.desc).take(howMany).list
  }

  def getLatestNotificationsBefore(recipient: Recipient, time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- activeRows if notif.recipient === recipient && notif.lastEvent < time
    } yield notif
    q.sortBy(_.lastEvent.desc).take(howMany).list
  }

  def getAllUnreadByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Seq[Notification] = {
    val kindStr = kind.name
    activeRows.filter(n => n.recipient === recipient && n.kind === kindStr && n.unread).list
  }

}

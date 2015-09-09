package com.keepit.eliza.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.model.{ NormalizedURI, User }
import com.keepit.notify.model.{ Recipient, NKind }
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationRepoImpl])
trait NotificationRepo extends Repo[Notification] with ExternalIdColumnFunction[Notification] {

  def getLastByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Option[Notification]

  def getByGroupIdentifier(recipient: Recipient, kind: NKind, identifier: String)(implicit session: RSession): Option[Notification]

  def getUnreadEnabledNotificationsCount(recipient: Recipient)(implicit session: RSession): Int

  def getUnreadEnabledNotificationsCountForKind(recipient: Recipient, kind: String)(implicit session: RSession): Int

  def getUnreadEnabledNotificationsCountExceptKind(recipient: Recipient, kind: String)(implicit session: RSession): Int

  def setAllReadBefore(recipient: Recipient, time: DateTime)(implicit session: RWSession): Unit

  def getNotificationsForPage(recipient: Recipient, nUri: Id[NormalizedURI], howMany: Int)(implicit session: RSession): Seq[Notification]

  def getNotificationsForPageBefore(recipient: Recipient, nUri: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification]

}

@Singleton
class NotificationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val userThreadRepoImpl: UserThreadRepoImpl,
    val clock: Clock) extends NotificationRepo with DbRepo[Notification] with ExternalIdColumnDbFunction[Notification] {

  import db.Driver.simple._

  type RepoImpl = NotificationTable
  class NotificationTable(tag: Tag) extends RepoTable[Notification](db, tag, "notification") with ExternalIdColumn[Notification] {

    def recipient = column[Recipient]("recipient", O.NotNull)
    def lastChecked = column[Option[DateTime]]("last_checked", O.NotNull)
    def kind = column[String]("kind", O.NotNull)
    def groupIdentifier = column[Option[String]]("group_identifier", O.Nullable)
    def lastEvent = column[DateTime]("last_event", O.NotNull)
    def disabled = column[Boolean]("disabled", O.NotNull)

    def hasNewEvent: Column[Boolean] = lastChecked.getOrElse(START_OF_TIME) < lastEvent
    def unread: Column[Boolean] = !disabled && hasNewEvent

    def * = (id.?, createdAt, updatedAt, lastChecked, kind, groupIdentifier, recipient, lastEvent, disabled, externalId) <> ((Notification.applyFromDbRow _).tupled, Notification.unapplyToDbRow)

  }

  def table(tag: Tag): NotificationTable = new NotificationTable(tag)
  initTable()

  def deleteCache(model: Notification)(implicit session: RSession): Unit = {}

  def invalidateCache(model: Notification)(implicit session: RSession): Unit = {}

  def getLastByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    val query = (for (row <- rows if row.recipient === recipient && row.kind === kindStr) yield row).sortBy(_.lastEvent.desc)
    query.firstOption
  }

  def getByGroupIdentifier(recipient: Recipient, kind: NKind, identifier: String)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    val q = for (row <- rows if row.recipient === recipient && row.groupIdentifier === identifier && row.kind === kindStr) yield row
    q.firstOption
  }

  def getUnreadEnabledNotificationsCount(recipient: Recipient)(implicit session: RSession): Int = {
    val unread = for (
      row <- rows if row.recipient === recipient && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def getUnreadEnabledNotificationsCountForKind(recipient: Recipient, kind: String)(implicit session: RSession): Int = {
    val unread = for (
      row <- rows if row.recipient === recipient && row.kind === kind && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def getUnreadEnabledNotificationsCountExceptKind(recipient: Recipient, kind: String)(implicit session: RSession): Int = {
    val unread = for (
      row <- rows if row.recipient === recipient && row.kind =!= kind && row.unread
    ) yield row
    val unreadCount = unread.length
    unreadCount.run
  }

  def setAllReadBefore(recipient: Recipient, time: DateTime)(implicit session: RWSession): Unit = {
    val q = for (
      row <- rows if row.recipient === recipient && row.unread && row.lastEvent < time
    ) yield row.lastChecked
    q.update(Some(clock.now()))
  }

  def getNotificationsForPage(recipient: Recipient, nUri: Id[NormalizedURI], howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- rows if notif.recipient === recipient
      userThread <- userThreadRepoImpl.rows if userThread.notificationId === notif.id && userThread.uriId === nUri
    } yield notif
    val finalQ = q.sortBy(_.lastEvent.desc).take(howMany)
    val Qstr = finalQ.selectStatement
    finalQ.list
  }

  def getNotificationsForPageBefore(recipient: Recipient, nUri: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): Seq[Notification] = {
    val q = for {
      notif <- rows if notif.recipient === recipient && notif.lastEvent < time
      userThread <- userThreadRepoImpl.rows if userThread.notificationId === notif.id && userThread.uriId === nUri
    } yield notif
    q.sortBy(_.lastEvent.desc).take(howMany).list
  }

}

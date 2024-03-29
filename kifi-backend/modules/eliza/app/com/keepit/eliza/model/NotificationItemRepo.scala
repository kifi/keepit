package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.time.Clock
import com.keepit.notify.model.event.NotificationEvent
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationItemRepoImpl])
trait NotificationItemRepo extends Repo[NotificationItem] with ExternalIdColumnFunction[NotificationItem] {

  def getAllForNotification(notification: Id[Notification])(implicit session: RSession): Seq[NotificationItem]

}

@Singleton
class NotificationItemRepoImpl @Inject() (
    override val db: DataBaseComponent,
    override val clock: Clock) extends NotificationItemRepo with DbRepo[NotificationItem] with ExternalIdColumnDbFunction[NotificationItem] {

  import db.Driver.simple._

  type RepoImpl = NotificationItemTable
  class NotificationItemTable(tag: Tag) extends RepoTable[NotificationItem](db, tag, "notification_item") with ExternalIdColumn[NotificationItem] {

    def notificationId = column[Id[Notification]]("notification_id", O.NotNull)
    // Use a string here because a type like NotificationKind[_ <: NotificationEvent] just doesn't work
    def kind = column[String]("kind", O.NotNull)
    def event = column[NotificationEvent]("event", O.NotNull)
    def eventTime = column[DateTime]("event_time", O.NotNull)

    def * = (id.?, createdAt, updatedAt, notificationId, kind, event, externalId, eventTime) <> ((NotificationItem.applyFromDbRow _).tupled, NotificationItem.unapplyToDbRow)

  }

  def table(tag: Tag): NotificationItemTable = new NotificationItemTable(tag)
  initTable()

  def deleteCache(model: NotificationItem)(implicit session: RSession): Unit = {}

  def invalidateCache(model: NotificationItem)(implicit session: RSession): Unit = {}

  def getAllForNotification(notification: Id[Notification])(implicit session: RSession): Seq[NotificationItem] = {
    val q = for (row <- rows if row.notificationId === notification) yield row
    q.list
  }

}

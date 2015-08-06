package com.keepit.eliza.model

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, Repo, DbRepo }
import com.keepit.common.db.{ Model, Id }
import com.keepit.common.time._
import com.keepit.model.User
import com.keepit.notify.model.{ NKind, NotificationKind, NotificationEvent }
import org.joda.time.DateTime
import play.api.libs.json.JsObject

import scala.slick.lifted.ProvenShape

case class NotificationItem(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: NKind,
    event: NotificationEvent) extends Model[NotificationItem] {

  override def withId(id: Id[NotificationItem]): NotificationItem = copy(id = Some(id))

  override def withUpdateTime(now: DateTime): NotificationItem = copy(updatedAt = now)

}

object NotificationItem {

  def applyFromDbRow(
    id: Option[Id[NotificationItem]],
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    notificationId: Id[Notification],
    kind: String,
    event: NotificationEvent): NotificationItem =
    NotificationItem(
      id,
      createdAt,
      updatedAt,
      notificationId,
      NotificationKind.getByName(kind).get,
      event
    )

  def unapplyToDbRow(item: NotificationItem): Option[(Option[Id[NotificationItem]], DateTime, DateTime, Id[Notification], String, NotificationEvent)] =
    Some(
      item.id,
      item.createdAt,
      item.updatedAt,
      item.notificationId,
      item.kind.name,
      item.event
    )

}

@ImplementedBy(classOf[NotificationItemRepoImpl])
trait NotificationItemRepo extends Repo[NotificationItem] {

  def getAllForNotification(notification: Id[Notification]): Seq[NotificationItem]

}

@Singleton
class NotificationItemRepoImpl @Inject() (
    override val db: DataBaseComponent,
    override val clock: Clock) extends NotificationItemRepo with DbRepo[NotificationItem] {

  import db.Driver.simple._

  type RepoImpl = NotificationItemTable
  class NotificationItemTable(tag: Tag) extends RepoTable[NotificationItem](db, tag, "notification_item") {

    def notificationId = column[Id[Notification]]("notification_id", O.NotNull)
    // Use a string here because a type like NotificationKind[_ <: NotificationEvent] just doesn't work
    def kind = column[String]("kind", O.NotNull)
    def event = column[NotificationEvent]("event", O.NotNull)

    def * = (id.?, createdAt, updatedAt, notificationId, kind, event) <> ((NotificationItem.applyFromDbRow _).tupled, NotificationItem.unapplyToDbRow)

  }

  def table(tag: Tag): NotificationItemTable = new NotificationItemTable(tag)
  initTable()

  def deleteCache(model: NotificationItem)(implicit session: RSession): Unit = {}

  def invalidateCache(model: NotificationItem)(implicit session: RSession): Unit = {}

  def getAllForNotification(notification: Id[Notification]): Seq[NotificationItem] = ???

}

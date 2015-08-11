package com.keepit.eliza.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.time.Clock
import com.keepit.model.User
import com.keepit.notify.model.{ Recipient, NKind }
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationRepoImpl])
trait NotificationRepo extends Repo[Notification] {

  def getLastByRecipientAndKind(recipient: Recipient, kind: NKind)(implicit session: RSession): Option[Notification]

  def getByKindAndGroupIdentifier(kind: NKind, identifier: String)(implicit session: RSession): Option[Notification]

}

@Singleton
class NotificationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends NotificationRepo with DbRepo[Notification] {

  import db.Driver.simple._

  type RepoImpl = NotificationTable
  class NotificationTable(tag: Tag) extends RepoTable[Notification](db, tag, "notification") {

    def recipient = column[Recipient]("recipient", O.NotNull)
    def lastChecked = column[DateTime]("last_checked", O.NotNull)
    def kind = column[String]("kind", O.NotNull)
    def groupIdentifier = column[String]("group_identifier", O.Nullable)
    def lastEvent = column[DateTime]("last_event", O.NotNull)
    def disabled = column[Boolean]("disabled", O.NotNull)

    def * = (id.?, createdAt, updatedAt, lastChecked, kind, groupIdentifier.?, recipient, lastEvent, disabled) <> ((Notification.applyFromDbRow _).tupled, Notification.unapplyToDbRow)

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

  def getByKindAndGroupIdentifier(kind: NKind, identifier: String)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    val q = for (row <- rows if row.groupIdentifier === identifier && row.kind === kindStr) yield row
    q.firstOption
  }

}

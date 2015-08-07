package com.keepit.eliza.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.time.Clock
import com.keepit.model.User
import com.keepit.notify.model.NKind
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationRepoImpl])
trait NotificationRepo extends Repo[Notification] {

  def getLastByUserAndKind(userId: Id[User], kind: NKind)(implicit session: RSession): Option[Notification]

}

@Singleton
class NotificationRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends NotificationRepo with DbRepo[Notification] {

  import db.Driver.simple._

  type RepoImpl = NotificationTable
  class NotificationTable(tag: Tag) extends RepoTable[Notification](db, tag, "notification") {

    def userId = column[Id[User]]("user_id", O.NotNull)
    def lastChecked = column[DateTime]("last_checked", O.NotNull)
    def kind = column[String]("kind", O.NotNull)

    def * = (id.?, createdAt, updatedAt, userId, lastChecked, kind) <> ((Notification.applyFromDbRow _).tupled, Notification.unapplyToDbRow)

  }

  def table(tag: Tag): NotificationTable = new NotificationTable(tag)
  initTable()

  def deleteCache(model: Notification)(implicit session: RSession): Unit = {}

  def invalidateCache(model: Notification)(implicit session: RSession): Unit = {}

  def getLastByUserAndKind(userId: Id[User], kind: NKind)(implicit session: RSession): Option[Notification] = {
    val kindStr = kind.name
    val query = (for (row <- rows if row.userId === userId && row.kind === kindStr) yield row).sortBy(_.createdAt.desc)
    query.firstOption
  }

}

package com.keepit.eliza.model

import com.google.inject.{ ImplementedBy, Singleton, Inject }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.time.Clock
import com.keepit.model.User
import org.joda.time.DateTime

@ImplementedBy(classOf[NotificationRepoImpl])
trait NotificationRepo extends Repo[Notification] {

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

    def * = (id.?, createdAt, updatedAt, userId, lastChecked, kind) <> ((Notification.applyFromDbRow _).tupled, Notification.unapplyFromDbRow)

  }

  def table(tag: Tag): NotificationTable = new NotificationTable(tag)
  initTable()

  def deleteCache(model: Notification)(implicit session: RSession): Unit = {}

  def invalidateCache(model: Notification)(implicit session: RSession): Unit = {}

}

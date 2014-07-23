package com.keepit.curator.model

import com.google.inject.{ Singleton, Inject, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo }
import com.keepit.common.time.Clock
import com.keepit.model.User
import org.joda.time.DateTime

@ImplementedBy(classOf[UserTrackItemRepoImpl])
trait UserTrackItemRepo extends DbRepo[CuratorUserTrackItem] {
  def getByUserId(userId: Id[User])(implicit session: RSession): Option[CuratorUserTrackItem]
}

@Singleton
class UserTrackItemRepoImpl @Inject() (
    val db: DataBaseComponent,
    val clock: Clock) extends DbRepo[CuratorUserTrackItem] with UserTrackItemRepo {

  import db.Driver.simple._

  class UserTrackItemTable(tag: Tag) extends RepoTable[CuratorUserTrackItem](db, tag, "user_track_item") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def lastSeen = column[DateTime]("last_seen", O.NotNull)

    def * = (id.?, userId, lastSeen) <> ((CuratorUserTrackItem.apply _).tupled, CuratorUserTrackItem.unapply _)
  }

  type RepoImpl = UserTrackItemTable

  def table(tag: Tag) = new UserTrackItemTable(tag)
  initTable()

  def getByUserId(userId: Id[User])(implicit session: RSession): Option[CuratorUserTrackItem] = {
    (for (row <- rows if row.userId === userId) yield row).firstOption
  }

  def deleteCache(model: CuratorUserTrackItem)(implicit session: RSession): Unit = {}

  def invalidateCache(model: CuratorUserTrackItem)(implicit session: RSession): Unit = {}
}


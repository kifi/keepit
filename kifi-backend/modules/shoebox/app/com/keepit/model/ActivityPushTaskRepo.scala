package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time.Clock
import org.joda.time.{ LocalTime, DateTime }

import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[ActivityPushTaskRepoImpl])
trait ActivityPushTaskRepo extends Repo[ActivityPushTask] {
  def getByUser(userId: Id[User])(implicit session: RSession): Option[ActivityPushTask]
  def getByPushAndActivity(pushTimeAfter: DateTime, lastActivityAfter: LocalTime, limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]]
}

@Singleton
class ActivityPushTaskRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[ActivityPushTask] with ActivityPushTaskRepo with Logging {

  import DBSession._
  import db.Driver.simple._

  type RepoImpl = ActivityPushTable

  class ActivityPushTable(tag: Tag) extends RepoTable[ActivityPushTask](db, tag, "activity_push_task") {
    def userId = column[Id[User]]("user_id", O.Nullable)
    def lastPush = column[Option[DateTime]]("last_push", O.Nullable)
    def lastActive = column[DateTime]("last_active", O.NotNull)
    def * = (id.?, createdAt, updatedAt, userId, state, lastPush, lastActive) <> ((ActivityPushTask.apply _).tupled, ActivityPushTask.unapply)
  }

  def table(tag: Tag) = new ActivityPushTable(tag)

  initTable()

  override def deleteCache(activityPushTask: ActivityPushTask)(implicit session: RSession): Unit = {}

  override def invalidateCache(activityPushTask: ActivityPushTask)(implicit session: RSession): Unit = {}

  def getByUser(id: Id[User])(implicit session: RSession): Option[ActivityPushTask] = {
    (for (b <- rows if b.userId === id) yield b).firstOption
  }

  def getByPushAndActivity(pushTimeAfter: DateTime, activeTimeAfter: LocalTime, limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]] = {
    import StaticQuery.interpolation
    sql"select id from activity_push_task where state = 'active' and last_push > $pushTimeAfter and time(last_active) > $activeTimeAfter limit $limit".as[Id[ActivityPushTask]].list
  }
}


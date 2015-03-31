package com.keepit.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.{ LocalTime, DateTime }
import scala.concurrent.duration._
import scala.concurrent.duration.Duration._

import scala.concurrent.duration.Duration
import scala.slick.jdbc.StaticQuery

@ImplementedBy(classOf[ActivityPushTaskRepoImpl])
trait ActivityPushTaskRepo extends Repo[ActivityPushTask] {
  def getByUser(userId: Id[User])(implicit session: RSession): Option[ActivityPushTask]
  def getByPushAndActivity(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]]
  def getUsersWithoutActivityPushTask(limit: Int)(implicit session: RSession): Seq[Id[User]]
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
    def lastActiveTime = column[LocalTime]("last_active_time", O.NotNull)
    def lastActiveDate = column[DateTime]("last_active_date", O.NotNull)
    def nextPush = column[Option[DateTime]]("next_push", O.Nullable)
    def backoff = column[Option[Duration]]("backoff", O.Nullable)

    def * = (id.?, createdAt, updatedAt, userId, state, lastPush, lastActiveTime, lastActiveDate, nextPush, backoff) <> ((ActivityPushTask.apply _).tupled, ActivityPushTask.unapply)
  }

  def table(tag: Tag) = new ActivityPushTable(tag)

  initTable()

  override def deleteCache(activityPushTask: ActivityPushTask)(implicit session: RSession): Unit = {}

  override def invalidateCache(activityPushTask: ActivityPushTask)(implicit session: RSession): Unit = {}

  def getByUser(id: Id[User])(implicit session: RSession): Option[ActivityPushTask] = {
    (for (b <- rows if b.userId === id) yield b).firstOption
  }

  def getByPushAndActivity(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val now = clock.now
    sql"select id from activity_push_task where state = 'active' and ((last_push is null) or (next_push > $now)) limit $limit".as[Id[ActivityPushTask]].list
  }

  def getUsersWithoutActivityPushTask(limit: Int)(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"select user.id from user left join activity_push_task on user.id = activity_push_task.user_id where activity_push_task.user_id is null limit $limit".as[Id[User]].list
  }
}


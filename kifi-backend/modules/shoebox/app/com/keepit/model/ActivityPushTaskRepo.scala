package com.keepit.model

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick._
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import org.joda.time.{ DateTime, LocalTime }

import scala.concurrent.duration._

@ImplementedBy(classOf[ActivityPushTaskRepoImpl])
trait ActivityPushTaskRepo extends Repo[ActivityPushTask] {
  def getByUser(userId: Id[User])(implicit session: RSession): Option[ActivityPushTask]
  def getBatchToPush(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]]
  def getBatchNoDevicesToPush(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]]
  def getMobileUsersWithoutActivityPushTask(limit: Int)(implicit session: RSession): Seq[Id[User]]
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

  def getBatchToPush(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val now = clock.now
    sql"select id from activity_push_task where state = 'active' and (next_push < $now or next_push is null) limit $limit".as[Id[ActivityPushTask]].list
  }

  def getBatchNoDevicesToPush(limit: Int)(implicit session: RSession): Seq[Id[ActivityPushTask]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    val now = clock.now
    sql"select id from activity_push_task where state = 'no_devices' and (next_push < $now or next_push is null) limit $limit".as[Id[ActivityPushTask]].list
  }

  def getMobileUsersWithoutActivityPushTask(limit: Int)(implicit session: RSession): Seq[Id[User]] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    sql"select distinct ki.user_id from kifi_installation ki left join activity_push_task on ki.user_id = activity_push_task.user_id where activity_push_task.user_id is null and ki.state = 'active' and ki.platform in ('#${KifiInstallationPlatform.IPhone.name}', '#${KifiInstallationPlatform.Android.name}') limit $limit".as[Id[User]].list
  }
}


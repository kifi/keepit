package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }

import com.keepit.common.db.slick.{ Repo, RepoWithDelete, DbRepo, DbRepoWithDelete, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.common.db.slick.SQLInterpolation_WarningsFixed
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.model.{ User, NormalizedURI }

import org.joda.time.DateTime

import play.api.libs.json.{ JsValue, JsNull }

import scala.slick.jdbc.StaticQuery

object UserThreadRepo {
  type RawNotification = (JsValue, Boolean, Option[Id[NormalizedURI]]) // lastNotification, unread, uriId
}

@ImplementedBy(classOf[UserThreadRepoImpl])
trait UserThreadRepo extends Repo[UserThread] with RepoWithDelete[UserThread] {
  def getUserThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread]
  def getLatestUnreadUnmutedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[UserThread]
  def getThreadIds(user: Id[User], uriId: Option[Id[NormalizedURI]] = None)(implicit session: RSession): Seq[Id[MessageThread]]
  def markAllRead(user: Id[User])(implicit session: RWSession): Unit
  def markAllReadAtOrBefore(user: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit
  def setNotification(user: Id[User], thread: Id[MessageThread], message: ElizaMessage, notifJson: JsValue, unread: Boolean)(implicit session: RWSession): Unit
  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession): Unit
  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession): Seq[UserThreadNotification]
  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession): Int
  def getRawNotification(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): RawNotification
  def getLatestRawNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification]
  def getRawNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification]
  def getLatestUnreadRawNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification]
  def getUnreadRawNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification]
  def getLatestRawNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification]
  def getRawNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification]
  def getLatestRawNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): List[RawNotification]
  def getRawNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification]
  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): (Int, Int)
  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int
  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): (Int, Int)
  def getUnreadThreadCount(userId: Id[User])(implicit session: RSession): Int
  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread
  def markRead(userId: Id[User], threadId: Id[MessageThread], msg: ElizaMessage)(implicit session: RWSession): Unit
  def getUserThreadsForEmailing(lastNotifiedBefore: DateTime)(implicit session: RSession): Seq[UserThread]
  def setNotificationEmailed(id: Id[UserThread], relevantMessage: Option[Id[ElizaMessage]])(implicit session: RWSession): Unit
  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit
  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession): Boolean
  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[ElizaMessage], newJson: JsValue)(implicit session: RWSession): Unit
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread]
  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Boolean
  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession): Unit
  def getThreadActivity(theadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity]
  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats
  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean
  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean]
  def getByThread(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThread]
  def getThreadStarter(threadId: Id[MessageThread])(implicit session: RSession): Id[User]
  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[UserThread]
  def getNotificationByThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Option[RawNotification]
  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]
  def getSharedThreadsForGroup(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]
  def getAllThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]
}

/**
 * If we ever add cache to this repo and need to invalidate it then pay attention to the update statments!
 */
@Singleton
class UserThreadRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent,
    userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache) extends UserThreadRepo with DbRepo[UserThread] with DbRepoWithDelete[UserThread] with MessagingTypeMappers with Logging {

  import db.Driver.simple._

  type RepoImpl = UserThreadTable
  class UserThreadTable(tag: Tag) extends RepoTable[UserThread](db, tag, "user_thread") {
    def user = column[Id[User]]("user_id", O.NotNull)
    def threadId = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Option[Id[NormalizedURI]]]("uri_id", O.Nullable)
    def lastSeen = column[Option[DateTime]]("last_seen", O.Nullable)
    def unread = column[Boolean]("notification_pending", O.NotNull)
    def muted = column[Boolean]("muted", O.NotNull)
    def lastMsgFromOther = column[Option[Id[ElizaMessage]]]("last_msg_from_other", O.Nullable)
    def lastNotification = column[JsValue]("last_notification", O.NotNull)
    def notificationUpdatedAt = column[DateTime]("notification_updated_at", O.NotNull)
    def notificationLastSeen = column[Option[DateTime]]("notification_last_seen", O.Nullable)
    def notificationEmailed = column[Boolean]("notification_emailed", O.NotNull)
    def lastActive = column[Option[DateTime]]("last_active", O.Nullable)
    def started = column[Boolean]("started", O.NotNull)
    def accessToken = column[ThreadAccessToken]("access_token", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, user, threadId, uriId, lastSeen, unread, muted, lastMsgFromOther, lastNotification, notificationUpdatedAt, notificationLastSeen, notificationEmailed, lastActive, started, accessToken) <> ((UserThread.apply _).tupled, UserThread.unapply _)

    def userThreadIndex = index("user_thread", (user, threadId), unique = true)
  }

  def table(tag: Tag) = new UserThreadTable(tag)
  initTable()

  override def deleteCache(model: UserThread)(implicit session: RSession): Unit = {
    userThreadStatsForUserIdCache.remove(UserThreadStatsForUserIdKey(model.user))
  }

  override def invalidateCache(model: UserThread)(implicit session: RSession): Unit = {
    userThreadStatsForUserIdCache.remove(UserThreadStatsForUserIdKey(model.user))
  }

  //recording persisted user threads only for the sake of understanding why user messages are sent very late in ElizaEmailNotifierActor
  //once we'll figure it out this save method can be removed
  override def save(model: UserThread)(implicit session: RWSession): UserThread = {
    val saved = super.save(model)
    log.info(s"persisting: ${model.summary}")
    saved
  }

  def getUserThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread] = {
    rows.filter(r => r.user === userId && r.uriId === uriId).list
  }

  def getLatestUnreadUnmutedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[UserThread] = {
    rows
      .filter(row => row.user === userId && row.unread && !row.muted)
      .sortBy(row => row.notificationUpdatedAt desc)
      .take(howMany)
      .list
  }

  def getThreadIds(userId: Id[User], uriIdOpt: Option[Id[NormalizedURI]] = None)(implicit session: RSession): Seq[Id[MessageThread]] = {
    uriIdOpt.map { uriId => rows.filter(row => row.user === userId && row.uriId === uriId) }
      .getOrElse { rows.filter(row => row.user === userId) }
      .map(_.threadId).list
  }

  def markAllRead(user: Id[User])(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(row => row.user === user && row.unread).map(row => (row.unread, row.updatedAt)).update((false, now))
  }

  def markAllReadAtOrBefore(userId: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(row => row.user === userId && row.notificationUpdatedAt <= timeCutoff).map(row => (row.unread, row.updatedAt)).update((false, now))
  }

  def setNotification(userId: Id[User], threadId: Id[MessageThread], message: ElizaMessage, notifJson: JsValue, unread: Boolean)(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(row => (row.user === userId && row.threadId === threadId) && (row.lastMsgFromOther.isEmpty || row.lastMsgFromOther < message.id.get))
      .map(row => (row.lastNotification, row.lastMsgFromOther, row.unread, row.notificationUpdatedAt, row.notificationEmailed, row.updatedAt))
      .update((notifJson, Some(message.id.get), unread, message.createdAt, false, now))

    rows.filter(row => (row.user === userId && row.threadId === threadId) && row.lastMsgFromOther === message.id.get)
      .map(row => (row.lastNotification, row.notificationEmailed, row.updatedAt))
      .update((notifJson, false, now))
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession): Unit = { // Note: minor race condition
    val now = clock.now
    rows
      .filter(row => row.user === userId && row.threadId === threadId && (row.lastSeen < timestamp || row.lastSeen.isEmpty))
      .map(row => (row.lastSeen, row.updatedAt))
      .update((Some(timestamp), now))
  }

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession): Seq[UserThreadNotification] = {
    rows.filter(row => row.user === userId && row.unread).map(row => (row.threadId, row.lastMsgFromOther)).list.map {
      case (thread, message) => UserThreadNotification(thread, message.get)
    }
  }

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession) = {
    val now = clock.now
    rows.filter(row => row.id === userThreadId).map(row => (row.muted, row.updatedAt)).update((muted, now))
  }

  def getRawNotification(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): RawNotification = {
    rows.filter(row => row.user === userId && row.threadId === threadId).map(row => (row.lastNotification, row.unread, row.uriId)).first
  }

  def getLatestRawNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getRawNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.notificationUpdatedAt < time && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getLatestUnreadRawNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.unread && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getUnreadRawNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.unread && row.notificationUpdatedAt < time && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getLatestRawNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.started && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getRawNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.started && row.notificationUpdatedAt < time && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getLatestRawNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.uriId === uriId && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getRawNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): List[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.uriId === uriId && row.notificationUpdatedAt < time && row.lastNotification =!= (JsNull: JsValue))
      .sortBy(row => row.notificationUpdatedAt desc)
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .take(howMany)
      .list
  }

  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): (Int, Int) = {
    StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(notification_pending and not muted) from user_thread where user_id = $userId and uri_id = $uriId").first
  }

  def getUnreadThreadCount(userId: Id[User])(implicit session: RSession): Int = getUnreadThreadCounts(userId)._1
  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int = getUnreadThreadCounts(userId)._2

  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(not muted) from user_thread where user_id = $userId and notification_pending").first
  }

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread = {
    rows.filter(row => row.user === userId && row.threadId === threadId).first
  }

  def markRead(userId: Id[User], threadId: Id[MessageThread], message: ElizaMessage)(implicit session: RWSession): Unit = {
    // Potentially updating lastMsgFromOther (and notificationUpdatedAt for consistency) b/c notification JSON may not have been persisted yet.
    // Note that this method works properly even if the message is from this user. TODO: Rename lastMsgFromOther => lastMsgId ?
    val now = clock.now
    rows
      .filter(row => (row.user === userId && row.threadId === threadId) && (row.lastMsgFromOther.isEmpty || row.lastMsgFromOther <= message.id.get))
      .map(row => (row.lastMsgFromOther, row.unread, row.notificationUpdatedAt, row.updatedAt))
      .update((Some(message.id.get), false, message.createdAt, now))
  }

  def getUserThreadsForEmailing(lastNotifiedBefore: DateTime)(implicit session: RSession): Seq[UserThread] = {
    rows.filter(row => row.unread && !row.notificationEmailed && row.notificationUpdatedAt < lastNotifiedBefore).list
  }

  def setNotificationEmailed(id: Id[UserThread], relevantMessageOpt: Option[Id[ElizaMessage]])(implicit session: RWSession): Unit = {
    val now = clock.now
    relevantMessageOpt match {
      case Some(relevantMessage) => rows.filter(row => row.id === id && row.lastMsgFromOther === relevantMessage).map(_.notificationEmailed).update(true)
      case None => rows.filter(row => row.id === id).map(row => (row.notificationEmailed, row.updatedAt)).update((true, now))
    }
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit = {
    updates.foreach {
      case (oldId, newId) => rows.filter(row => row.uriId === oldId).map(_.uriId).update(Some(newId))
    }
  }

  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession): Boolean = {
    val now = clock.now
    rows.filter(row => row.user === userId && row.threadId === threadId && !row.unread).map(row => (row.unread, row.updatedAt)).update((true, now)) > 0
  }

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[ElizaMessage], newJson: JsValue)(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(row => row.user === userId && row.threadId === threadId && row.lastMsgFromOther === messageId).map(row => (row.lastNotification, row.updatedAt)).update((newJson, now))
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread] = {
    rows.filter(row => row.uriId === uriId).list
  }

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Boolean = {
    rows.filter(row => row.user === userId && row.threadId === threadId).map(_.muted).firstOption.getOrElse(false)
  }

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession): Unit = {
    val now = clock.now
    rows.filter(row => row.user === userId && row.threadId === threadId).map(row => (row.lastActive, row.updatedAt)).update((Some(lastActive), now))
  }

  def getThreadActivity(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity] = {
    rows
      .filter(row => row.threadId === threadId)
      .map(row => (row.id, row.threadId, row.user, row.lastActive, row.started, row.lastSeen))
      .list.map { tuple => (UserThreadActivity.apply _).tupled(tuple) }
  }

  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    userThreadStatsForUserIdCache.getOrElse(UserThreadStatsForUserIdKey(userId)) {
      UserThreadStats(
        all = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id}""".as[Int].first,
        active = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id} AND last_active IS NOT NULL""".as[Int].first,
        started = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id} AND started = TRUE""".as[Int].first)
    }
  }

  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (row <- rows if row.user === userId && row.uriId === uriId) yield row.id).firstOption.isDefined
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean] = {
    val uriSet = (for (row <- rows if row.user === userId && row.uriId.isDefined) yield row.uriId).list.toSet.flatten
    uriIds.map(uriId => uriSet.contains(uriId))
  }

  def getByThread(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThread] = {
    (for (row <- rows if row.threadId === threadId) yield row).list
  }

  def getThreadStarter(threadId: Id[MessageThread])(implicit session: RSession): Id[User] = {
    (for (row <- rows if row.threadId === threadId && row.started === true) yield row.user).first
  }

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[UserThread] = {
    (for (row <- rows if row.accessToken === token) yield row).firstOption
  }

  def getNotificationByThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Option[RawNotification] = {
    rows
      .filter(row => row.user === userId && row.threadId === threadId && row.lastNotification =!= (JsNull: JsValue))
      .map(row => (row.lastNotification, row.unread, row.uriId))
      .firstOption
  }

  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (users.isEmpty) Seq.empty[GroupThreadStats]
    else {
      val users_list = users.map(_.id).mkString(",")
      val queryStr = """
        select thread_id, created_at, count(*) as c from user_thread
          where user_id in (""" + users_list + """)
          and created_at >= '2015-1-1'
          group by thread_id
          having count(*) > 1
          order by week(created_at)
          desc
      """
      val query = new SQLInterpolation_WarningsFixed(StringContext(queryStr)).sql.as[(Long, DateTime, Int)]
      query.list.map((GroupThreadStats.apply _).tupled)
    }
  }

  def getSharedThreadsForGroup(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (users.isEmpty) Seq.empty[GroupThreadStats]
    else {
      val users_list = users.map(_.id).mkString(",")
      val queryStr = """
        select thread_id, created_at, count(*) as c from user_thread
          where user_id in (""" + users_list + """)
          group by thread_id
          having count(*) > 1
      """
      val query = new SQLInterpolation_WarningsFixed(StringContext(queryStr)).sql.as[(Long, DateTime, Int)]
      query.list.map((GroupThreadStats.apply _).tupled)
    }
  }

  def getAllThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (users.isEmpty) Seq.empty[GroupThreadStats]
    else {
      val users_list = users.map(_.id).mkString("(", ",", ")")
      sql"""
        select thread_id, created_at, count(*) as c from user_thread
          where user_id in #$users_list
          and created_at >= '2015-1-1'
          group by thread_id
          order by week(created_at)
          desc
      """.as[(Long, DateTime, Int)].list.map((GroupThreadStats.apply _).tupled)
    }
  }
}

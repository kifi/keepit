package com.keepit.eliza.model

import com.google.inject.{ Inject, Singleton, ImplementedBy }

import com.keepit.common.db.slick.{ Repo, RepoWithDelete, DbRepo, DbRepoWithDelete, DataBaseComponent }
import com.keepit.common.db.slick.DBSession.{ RWSession, RSession }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.core.anyExtensionOps
import com.keepit.common.db.Id
import com.keepit.common.db.slick.SQLInterpolation_WarningsFixed
import com.keepit.eliza.commanders.{ UnreadThreadCounts, UserThreadQuery }
import com.keepit.model.{ ElizaFeedFilter, FeedFilter, Keep, User, NormalizedURI }

import org.joda.time.DateTime

@ImplementedBy(classOf[UserThreadRepoImpl])
trait UserThreadRepo extends Repo[UserThread] with RepoWithDelete[UserThread] {
  def intern(model: UserThread)(implicit session: RWSession): UserThread
  // Simple lookup queries
  def getByKeep(keepId: Id[Keep])(implicit session: RSession): Seq[UserThread]
  def getUserThread(userId: Id[User], keepId: Id[Keep])(implicit session: RSession): Option[UserThread]
  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread]
  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[UserThread]

  // Complex lookup queries
  def getThreadsForUser(userId: Id[User], utq: UserThreadQuery)(implicit session: RSession): List[UserThread]

  // Stats queries
  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats
  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]
  def getSharedThreadsForGroup(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]
  def getAllThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats]

  // Things that need to be replaced with a single "rpb"-style query
  def getUserThreadsForEmailing(lastNotifiedBefore: DateTime)(implicit session: RSession): Seq[UserThread]
  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): UnreadThreadCounts
  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): UnreadThreadCounts
  def getUserThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread]
  def getLatestUnreadUnmutedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[UserThread]
  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession): Seq[UserThreadNotification]
  def getThreadStream(userId: Id[User], limit: Int, beforeId: Option[Id[Keep]], filter: ElizaFeedFilter)(implicit session: RSession): Map[Id[Keep], DateTime]

  // Single-use queries that are actually slower than just doing the sane thing
  def getThreadActivity(keepId: Id[Keep])(implicit session: RSession): Seq[UserThreadActivity]
  def getKeepIds(user: Id[User], uriId: Option[Id[NormalizedURI]] = None)(implicit session: RSession): Seq[Id[Keep]]
  def isMuted(userId: Id[User], keepId: Id[Keep])(implicit session: RSession): Boolean
  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean]
  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean

  // Handling read/unread
  def setLastActive(userId: Id[User], keepId: Id[Keep], lastActive: DateTime)(implicit session: RWSession): Unit
  def setLastSeen(userId: Id[User], keepId: Id[Keep], timestamp: DateTime)(implicit session: RWSession): Unit
  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession): Boolean
  def markAllReadAtOrBefore(user: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit
  def markRead(userId: Id[User], msg: ElizaMessage)(implicit session: RWSession): Unit
  def markAllRead(user: Id[User])(implicit session: RWSession): Unit
  def markUnread(userId: Id[User], keepId: Id[Keep])(implicit session: RWSession): Boolean

  // Mutating threads in-place
  def setNotificationEmailed(id: Id[UserThread], relevantMessage: Option[Id[ElizaMessage]])(implicit session: RWSession): Unit
  def registerMessage(msg: ElizaMessage)(implicit session: RWSession): Unit
  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit
  def deactivate(model: UserThread)(implicit session: RWSession): Unit
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
    def keepId = column[Id[Keep]]("keep_id", O.NotNull)
    def uriId = column[Option[Id[NormalizedURI]]]("uri_id", O.Nullable)
    def lastSeen = column[Option[DateTime]]("last_seen", O.Nullable)
    def unread = column[Boolean]("notification_pending", O.NotNull)
    def muted = column[Boolean]("muted", O.NotNull)
    def latestMessageId = column[Option[Id[ElizaMessage]]]("last_msg_from_other", O.Nullable)
    def notificationUpdatedAt = column[DateTime]("notification_updated_at", O.NotNull)
    def notificationEmailed = column[Boolean]("notification_emailed", O.NotNull)
    def lastActive = column[Option[DateTime]]("last_active", O.Nullable)
    def startedBy = column[Id[User]]("started_by", O.NotNull)
    def accessToken = column[ThreadAccessToken]("access_token", O.NotNull)
    def * = (id.?, createdAt, updatedAt, state, user, keepId, uriId, lastSeen, unread, muted, latestMessageId, notificationUpdatedAt, notificationEmailed, lastActive, startedBy, accessToken) <> ((UserThread.apply _).tupled, UserThread.unapply _)
  }

  def table(tag: Tag) = new UserThreadTable(tag)
  initTable()

  private def deadRows = rows.filter(_.state === UserThreadStates.INACTIVE)
  private def activeRows = rows.filter(_.state === UserThreadStates.ACTIVE)

  override def deleteCache(model: UserThread)(implicit session: RSession): Unit = {
    userThreadStatsForUserIdCache.remove(UserThreadStatsForUserIdKey(model.user))
  }

  override def invalidateCache(model: UserThread)(implicit session: RSession): Unit = {
    userThreadStatsForUserIdCache.remove(UserThreadStatsForUserIdKey(model.user))
  }

  def intern(model: UserThread)(implicit session: RWSession): UserThread = {
    // There is a unique index on (userId, keepId), so snake the id from any dead model that collides
    rows.filter(row => row.user === model.user && row.keepId === model.keepId).firstOption match {
      case Some(existingModel) if existingModel.isActive => existingModel
      case deadModelOpt => save(model.copy(id = deadModelOpt.map(_.id.get)))
    }
  }

  def getByKeep(keepId: Id[Keep])(implicit session: RSession): Seq[UserThread] = {
    (for (row <- activeRows if row.keepId === keepId) yield row).list
  }

  def getUserThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread] = {
    activeRows.filter(r => r.user === userId && r.uriId === uriId).list
  }

  def getLatestUnreadUnmutedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[UserThread] = {
    activeRows
      .filter(row => row.user === userId && row.unread && !row.muted)
      .sortBy(row => (row.notificationUpdatedAt desc, row.id desc))
      .take(howMany)
      .list
  }

  def getThreadStream(userId: Id[User], limit: Int, beforeId: Option[Id[Keep]], filter: ElizaFeedFilter)(implicit session: RSession): Map[Id[Keep], DateTime] = {
    val unmutedRows = activeRows.filter(row => row.state === UserThreadStates.ACTIVE && row.user === userId && !row.muted)
    val rowsBeforeId = beforeId match {
      case Some(keepId) =>
        val beforeTime = getUserThread(userId, keepId).map(_.notificationUpdatedAt)
        unmutedRows.filter(_.notificationUpdatedAt < beforeTime)
      case None => unmutedRows
    }
    val filteredRows = filter match {
      case FeedFilter.All => rowsBeforeId
      case FeedFilter.Unread => rowsBeforeId.filter(_.unread)
      case FeedFilter.Sent => rowsBeforeId.filter(_.startedBy === userId)
      case unknown => throw new Exception(s"invalid ElizaFeedFilter $unknown")
    }
    filteredRows
      .sortBy(_.notificationUpdatedAt desc)
      .take(limit)
      .map(row => (row.keepId, row.notificationUpdatedAt))
      .list.toMap
  }

  def getKeepIds(userId: Id[User], uriIdOpt: Option[Id[NormalizedURI]] = None)(implicit session: RSession): Seq[Id[Keep]] = {
    uriIdOpt.map { uriId => activeRows.filter(row => row.user === userId && row.uriId === uriId) }
      .getOrElse { activeRows.filter(row => row.user === userId) }
      .map(_.keepId).list
  }

  def markAllRead(user: Id[User])(implicit session: RWSession): Unit = {
    val now = clock.now
    activeRows.filter(row => row.user === user && row.unread).map(row => (row.unread, row.updatedAt)).update((false, now))
  }

  def markAllReadAtOrBefore(userId: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit = {
    val now = clock.now
    activeRows.filter(row => row.user === userId && row.notificationUpdatedAt <= timeCutoff).map(row => (row.unread, row.updatedAt)).update((false, now))
  }

  def setLastSeen(userId: Id[User], keepId: Id[Keep], timestamp: DateTime)(implicit session: RWSession): Unit = { // Note: minor race condition
    val now = clock.now
    activeRows
      .filter(row => row.user === userId && row.keepId === keepId && (row.lastSeen < timestamp || row.lastSeen.isEmpty))
      .map(row => (row.lastSeen, row.updatedAt))
      .update((Some(timestamp), now))
  }

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession): Seq[UserThreadNotification] = {
    activeRows.filter(row => row.user === userId && row.unread).map(row => (row.keepId, row.latestMessageId)).list.map {
      case (keep, message) => UserThreadNotification(keep, message.get)
    }
  }

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession) = {
    val now = clock.now
    activeRows.filter(row => row.id === userThreadId).map(row => (row.muted, row.updatedAt)).update((muted, now)) > 0
  }

  def getThreadsForUser(userId: Id[User], utq: UserThreadQuery)(implicit session: RSession): List[UserThread] = {
    val desiredThreads = activeRows |> { rs => // by user
      rs.filter(r => r.user === userId)
    } |> { rs =>
      utq.keepIds.map(keepIds => rs.filter(r => r.keepId.inSet(keepIds))).getOrElse(rs)
    } |> { rs => // by uri
      utq.onUri.map(uriId => rs.filter(r => r.uriId === uriId)).getOrElse(rs)
    } |> { rs => // by time
      utq.beforeTime.map(fromTime => rs.filter(r => r.notificationUpdatedAt < fromTime)).getOrElse(rs)
    } |> { rs => // by unread
      utq.onlyUnread.map(unread => rs.filter(r => r.unread === unread)).getOrElse(rs)
    } |> { rs => // by started
      utq.onlyStartedBy.map(starter => rs.filter(r => r.startedBy === starter)).getOrElse(rs)
    }

    desiredThreads
      .sortBy(_.notificationUpdatedAt desc)
      .take(utq.limit).list
  }

  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): UnreadThreadCounts = {
    val threads = activeRows.filter(ut => ut.user === userId && ut.uriId === uriId)
    val (total, unmuted) = (threads.length, threads.filter(t => t.unread && !t.muted).length).run
    UnreadThreadCounts(total, unmuted)
  }

  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): UnreadThreadCounts = {
    val threads = activeRows.filter(ut => ut.user === userId && ut.unread)
    val (total, unmuted) = (threads.length, threads.filter(!_.muted).length).run
    UnreadThreadCounts(total, unmuted)
  }

  def getUserThread(userId: Id[User], keepId: Id[Keep])(implicit session: RSession): Option[UserThread] = {
    activeRows.filter(row => row.user === userId && row.keepId === keepId).firstOption
  }

  def markRead(userId: Id[User], message: ElizaMessage)(implicit session: RWSession): Unit = {
    // Potentially updating lastMsgFromOther (and notificationUpdatedAt for consistency) b/c notification JSON may not have been persisted yet.
    // Note that this method works properly even if the message is from this user. TODO: Rename lastMsgFromOther => lastMsgId ?
    val now = clock.now
    activeRows
      .filter(row => (row.user === userId && row.keepId === message.keepId) && (row.latestMessageId.isEmpty || row.latestMessageId <= message.id.get))
      .map(row => (row.latestMessageId, row.unread, row.notificationUpdatedAt, row.updatedAt))
      .update((Some(message.id.get), false, message.createdAt, now))
  }

  def getUserThreadsForEmailing(lastNotifiedBefore: DateTime)(implicit session: RSession): Seq[UserThread] = {
    activeRows.filter(row => row.unread && !row.notificationEmailed && row.notificationUpdatedAt < lastNotifiedBefore).list
  }

  def setNotificationEmailed(id: Id[UserThread], relevantMessageOpt: Option[Id[ElizaMessage]])(implicit session: RWSession): Unit = {
    val now = clock.now
    relevantMessageOpt match {
      case Some(relevantMessage) => activeRows.filter(row => row.id === id && row.latestMessageId === relevantMessage).map(_.notificationEmailed).update(true)
      case None => activeRows.filter(row => row.id === id).map(row => (row.notificationEmailed, row.updatedAt)).update((true, now))
    }
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession): Unit = {
    updates.foreach {
      case (oldId, newId) => rows.filter(row => row.uriId === oldId).map(_.uriId).update(Some(newId))
    }
  }

  def markUnread(userId: Id[User], keepId: Id[Keep])(implicit session: RWSession): Boolean = {
    val now = clock.now
    activeRows.filter(row => row.user === userId && row.keepId === keepId && !row.unread).map(row => (row.unread, row.updatedAt)).update((true, now)) > 0
  }

  def registerMessage(msg: ElizaMessage)(implicit session: RWSession): Unit = {
    val now = clock.now
    activeRows
      .filter(row => row.keepId === msg.keepId)
      .map(row => (row.updatedAt, row.latestMessageId, row.notificationUpdatedAt))
      .update((now, Some(msg.id.get), msg.createdAt))
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession): Seq[UserThread] = {
    activeRows.filter(row => row.uriId === uriId).list
  }

  def isMuted(userId: Id[User], keepId: Id[Keep])(implicit session: RSession): Boolean = {
    activeRows.filter(row => row.user === userId && row.keepId === keepId).map(_.muted).firstOption.getOrElse(false)
  }

  def setLastActive(userId: Id[User], keepId: Id[Keep], lastActive: DateTime)(implicit session: RWSession): Unit = {
    val now = clock.now
    activeRows.filter(row => row.user === userId && row.keepId === keepId).map(row => (row.lastActive, row.updatedAt)).update((Some(lastActive), now))
  }

  def getThreadActivity(keepId: Id[Keep])(implicit session: RSession): Seq[UserThreadActivity] = {
    activeRows
      .filter(row => row.keepId === keepId)
      .map(row => (row.id, row.keepId, row.user, row.lastActive, row.startedBy === row.user, row.lastSeen))
      .list.map { tuple => (UserThreadActivity.apply _).tupled(tuple) }
  }

  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    userThreadStatsForUserIdCache.getOrElse(UserThreadStatsForUserIdKey(userId)) {
      UserThreadStats(
        all = activeRows.filter(row => row.user === userId).length.run,
        active = activeRows.filter(row => row.user === userId && row.lastActive.isDefined).length.run,
        started = activeRows.filter(row => row.user === userId && row.startedBy === userId).length.run
      )
    }
  }

  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
    (for (row <- activeRows if row.user === userId && row.uriId === uriId) yield row.id).firstOption.isDefined
  }

  def checkUrisDiscussed(userId: Id[User], uriIds: Seq[Id[NormalizedURI]])(implicit session: RSession): Seq[Boolean] = {
    val uriSet = (for (row <- activeRows if row.user === userId && row.uriId.isDefined) yield row.uriId).list.toSet.flatten
    uriIds.map(uriId => uriSet.contains(uriId))
  }

  def getByAccessToken(token: ThreadAccessToken)(implicit session: RSession): Option[UserThread] = {
    (for (row <- activeRows if row.accessToken === token) yield row).firstOption
  }

  def getSharedThreadsForGroupByWeek(users: Seq[Id[User]])(implicit session: RSession): Seq[GroupThreadStats] = {
    import com.keepit.common.db.slick.StaticQueryFixed.interpolation
    if (users.isEmpty) Seq.empty[GroupThreadStats]
    else {
      val users_list = users.map(_.id).mkString(",")
      val queryStr = """
        select keep_id, created_at, count(*) as c from user_thread
          where user_id in (""" + users_list + """)
          and state = 'active'
          and created_at >= '2015-1-1'
          group by keep_id
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
        select keep_id, created_at, count(*) as c from user_thread
          where user_id in (""" + users_list + """)
          and state = 'active'
          group by keep_id
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
        select keep_id, created_at, count(*) as c from user_thread
          where user_id in #$users_list
          and created_at >= '2015-1-1'
          and state = 'active'
          group by keep_id
          order by week(created_at)
          desc
      """.as[(Long, DateTime, Int)].list.map((GroupThreadStats.apply _).tupled)
    }
  }

  def deactivate(model: UserThread)(implicit session: RWSession): Unit = {
    save(model.sanitizeForDelete)
  }
}

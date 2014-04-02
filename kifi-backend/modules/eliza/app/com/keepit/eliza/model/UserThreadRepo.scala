package com.keepit.eliza.model

import com.keepit.common.db.slick.{Repo, RepoWithDelete, DbRepo, DbRepoWithDelete, DataBaseComponent}
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.Id
import com.keepit.model.{User, NormalizedURI}

import play.api.libs.json.{JsValue, JsNull}

import org.joda.time.DateTime

import com.google.inject.{Inject, Singleton, ImplementedBy}

import scala.slick.jdbc.StaticQuery
import com.keepit.eliza.commanders.RawNotifications


@ImplementedBy(classOf[UserThreadRepoImpl])
trait UserThreadRepo extends Repo[UserThread] with RepoWithDelete[UserThread] {

  def getThreadIds(user: Id[User], uriId: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]]

  def markAllRead(user: Id[User])(implicit session: RWSession) : Unit

  def markAllReadAtOrBefore(user: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit

  def setNotification(user: Id[User], thread: Id[MessageThread], message: Message, notifJson: JsValue, unread: Boolean)(implicit session: RWSession) : Unit

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification]

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession): Int

  def getLatestSendableNotificationsNotJustFromMe(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsNotJustFromMeBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsNotJustFromMeSince(userId: Id[User], time: DateTime)(implicit session: RSession): RawNotifications

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsSince(userId: Id[User], time: DateTime)(implicit session: RSession): RawNotifications

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications

  def getMutedSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getLatestSendableNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getLatestSendableNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): RawNotifications

  def getSendableNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications

  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): (Int, Int)

  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int

  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): (Int, Int)

  def getUnreadThreadCount(userId: Id[User])(implicit session: RSession): Int

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread

  def markRead(userId: Id[User], threadId: Id[MessageThread], msg: Message)(implicit session: RWSession): Unit

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread]

  def setNotificationEmailed(id: Id[UserThread], relevantMessage: Option[Id[Message]])(implicit session: RWSession) : Unit

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit

  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession): Boolean

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message], newJson: JsValue)(implicit session: RWSession) : Unit

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession) : Seq[UserThread]

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession) : Boolean

  def setNotificationJsonIfNotPresent(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue, message: Message)(implicit session: RWSession) : Unit

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession) : Unit

  def getThreadActivity(theadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity]

  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats

  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean

  def getByThread(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThread]

}

/**
 * If we ever add cache to this repo and need to invalidate it then pay attention to the update statments!
 */
@Singleton
class UserThreadRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent,
    userThreadStatsForUserIdCache: UserThreadStatsForUserIdCache
  )
  extends UserThreadRepo with DbRepo[UserThread] with DbRepoWithDelete[UserThread] with MessagingTypeMappers with Logging {

  import db.Driver.simple._

  type RepoImpl = UserThreadTable
  class UserThreadTable(tag: Tag) extends RepoTable[UserThread](db, tag, "user_thread") {
    def user = column[Id[User]]("user_id", O.NotNull)
    def thread = column[Id[MessageThread]]("thread_id", O.NotNull)
    def uriId = column[Id[NormalizedURI]]("uri_id", O.Nullable)
    def lastSeen = column[DateTime]("last_seen", O.Nullable)
    def unread = column[Boolean]("notification_pending", O.NotNull)
    def muted = column[Boolean]("muted", O.NotNull)
    def lastMsgFromOther = column[Id[Message]]("last_msg_from_other", O.Nullable)
    def lastNotification = column[JsValue]("last_notification", O.NotNull)
    def notificationUpdatedAt = column[DateTime]("notification_updated_at", O.NotNull)
    def notificationLastSeen = column[DateTime]("notification_last_seen", O.Nullable)
    def notificationEmailed = column[Boolean]("notification_emailed", O.NotNull)
    def replyable = column[Boolean]("replyable", O.NotNull)
    def lastActive = column[DateTime]("last_active", O.Nullable)
    def started = column[Boolean]("started", O.NotNull)
    def * = (id.?, createdAt, updatedAt, user, thread, uriId.?, lastSeen.?, unread, muted, lastMsgFromOther.?, lastNotification, notificationUpdatedAt, notificationLastSeen.?, notificationEmailed, replyable, lastActive.?, started) <> ((UserThread.apply _).tupled, UserThread.unapply _)

    def userThreadIndex = index("user_thread", (user,thread), unique=true)
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

  def getThreadIds(userId: Id[User], uriIdOpt: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]] = {
    uriIdOpt.map{ uriId =>
      (for (row <- rows if row.user===userId && row.uriId===uriId) yield row.thread).list
    } getOrElse {
      (for (row <- rows if row.user===userId) yield row.thread).list
    }
  }

  def markAllRead(user: Id[User])(implicit session: RWSession) : Unit = {
    (for (row <- rows if row.user === user) yield (row.unread, row.updatedAt)).update((false, clock.now()))
  }

  def markAllReadAtOrBefore(userId: Id[User], timeCutoff: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- rows if row.user === userId && row.notificationUpdatedAt <= timeCutoff) yield (row.unread, row.updatedAt)).update((false, clock.now()))
  }

  def setNotification(userId: Id[User], threadId: Id[MessageThread], message: Message, notifJson: JsValue, unread: Boolean)(implicit session: RWSession) : Unit = {
    rows.filter(row => (row.user === userId && row.thread === threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther < message.id.get))
      .map(row => (row.lastNotification, row.lastMsgFromOther, row.unread, row.notificationUpdatedAt, row.notificationEmailed, row.updatedAt))
      .update((notifJson, message.id.get, unread, message.createdAt, false, clock.now()))

    rows.filter(row => (row.user === userId && row.thread === threadId) && row.lastMsgFromOther === message.id.get)
      .map(row => (row.lastNotification, row.notificationEmailed, row.updatedAt))
      .update((notifJson, false, clock.now()))
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit = {  //Note: minor race condition
    (for (row <- rows if row.user===userId && row.thread===threadId && (row.lastSeen < timestamp || row.lastSeen.isNull)) yield (row.lastSeen, row.updatedAt)).update((timestamp, clock.now()))
  }

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification] = {
    (for (row <- rows if row.user===userId && row.unread) yield (row.thread, row.lastMsgFromOther.?)).list.map{
     case (thread, message) =>
        Notification(thread, message.get)
    }
  }

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession) = {
    (for (row <- rows if row.id === userThreadId) yield (row.muted, row.updatedAt)).update((muted, clock.now()))
  }

  def getLatestSendableNotificationsNotJustFromMe(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsNotJustFromMeBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.notificationUpdatedAt < time &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsNotJustFromMeSince(userId: Id[User], time: DateTime)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.notificationUpdatedAt > time &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsSince(userId: Id[User], time: DateTime)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.notificationUpdatedAt > time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.unread &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.unread &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.muted &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getMutedSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.muted &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getLatestSendableNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
     (for (row <- rows if row.user === userId &&
                           row.started &&
                           row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                           row.lastNotification.isNotNull) yield row)
     .sortBy(row => (row.notificationUpdatedAt) desc)
     .take(howMany).map(row => (row.lastNotification, row.unread))
     .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.started &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getLatestSendableNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.uriId === uriId &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getSendableNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): RawNotifications = {
    val rawNotifications =
      (for (row <- rows if row.user === userId &&
                            row.uriId === uriId &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => (row.lastNotification, row.unread))
      .list
    new RawNotifications(rawNotifications)
  }

  def getThreadCountsForUri(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): (Int, Int) = {
    StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(notification_pending and not muted) from user_thread where user_id = $userId and uri_id = $uriId").first
  }

  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int = {
    StaticQuery.queryNA[Int](s"select count(*) from user_thread where user_id = $userId and notification_pending and not muted").first
  }

  def getUnreadThreadCounts(userId: Id[User])(implicit session: RSession): (Int, Int) = {
    StaticQuery.queryNA[(Int, Int)](s"select count(*), sum(not muted) from user_thread where user_id = $userId and notification_pending").first
  }

  def getUnreadThreadCount(userId: Id[User])(implicit session: RSession): Int = {
    StaticQuery.queryNA[Int](s"select count(*) from user_thread where user_id = $userId and notification_pending").first
  }

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread = {
    (for (row <- rows if row.user === userId && row.thread === threadId) yield row).first
  }

  def markRead(userId: Id[User], threadId: Id[MessageThread], message: Message)(implicit session: RWSession): Unit = {
    // Potentially updating lastMsgFromOther (and notificationUpdatedAt for consistency) b/c notification JSON may not have been persisted yet.
    // Note that this method works properly even if the message is from this user. TODO: Rename lastMsgFromOther => lastMsgId ?
    rows.filter(row => (row.user === userId && row.thread === threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther <= message.id.get))
      .map(row => (row.lastMsgFromOther, row.unread, row.notificationUpdatedAt, row.updatedAt))
      .update((message.id.get, false, message.createdAt, clock.now()))
  }

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread] = {
    (for (row <- rows if row.replyable && row.unread && !row.notificationEmailed && row.notificationUpdatedAt < before) yield row).list
  }

  def setNotificationEmailed(id: Id[UserThread], relevantMessageOpt: Option[Id[Message]])(implicit session: RWSession) : Unit = {
    relevantMessageOpt.map{ relevantMessage =>
      (for (row <- rows if row.id === id && row.lastMsgFromOther === relevantMessage) yield row.notificationEmailed).update(true)
    } getOrElse {
      (for (row <- rows if row.id === id) yield (row.notificationEmailed, row.updatedAt)).update((true, clock.now()))
    }
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newId) =>
      (for (row <- rows if row.uriId===oldId) yield row.uriId).update(newId)
    }
  }

  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession): Boolean = {
    (for (row <- rows if row.user === userId && row.thread === threadId && !row.unread) yield (row.unread, row.updatedAt)).update((true, clock.now())) > 0
  }

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message], newJson: JsValue)(implicit session: RWSession) : Unit = {
    (for (row <- rows if row.user===userId &&row.thread===threadId && row.lastMsgFromOther===messageId) yield (row.lastNotification, row.updatedAt)).update((newJson, clock.now()))
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession) : Seq[UserThread] = {
    (for (row <- rows if row.uriId===uriId) yield row).list
  }

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Boolean = {
    (for (row <- rows if row.user===userId && row.thread===threadId) yield row.muted).firstOption.getOrElse(false)
  }

  def setNotificationJsonIfNotPresent(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue, message: Message)(implicit session: RWSession) : Unit = {
    (for (row <- rows if row.user===userId && row.thread===threadId && row.lastMsgFromOther.isNull) yield (row.lastNotification, row.notificationUpdatedAt)).update((notifJson, message.createdAt))
  }

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- rows if row.user===userId && row.thread===threadId) yield (row.lastActive, row.updatedAt)).update((lastActive, clock.now()))
  }

  def getThreadActivity(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity] = {
    val rawData = (for (row <- rows if row.thread === threadId) yield (row.id, row.thread, row.user, row.lastActive.?, row.started, row.lastSeen.?)).list
    rawData.map{tuple => (UserThreadActivity.apply _).tupled(tuple) }
  }

  def getUserStats(userId: Id[User])(implicit session: RSession): UserThreadStats = {
    import StaticQuery.interpolation
    userThreadStatsForUserIdCache.getOrElse(UserThreadStatsForUserIdKey(userId)) {
      UserThreadStats(
        all = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id}""".as[Int].first,
        active = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id} AND last_active IS NOT NULL""".as[Int].first,
        started = sql"""SELECT count(*) FROM user_thread WHERE user_id=${userId.id} AND started = TRUE""".as[Int].first)
    }
  }

  def hasThreads(userId: Id[User], uriId: Id[NormalizedURI])(implicit session: RSession): Boolean = {
   (for (row <- rows if row.user===userId && row.uriId===uriId) yield row.id).firstOption.isDefined
  }

  def getByThread(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThread] = {
    (for (row <- rows if row.thread===threadId) yield row).list
  }

}

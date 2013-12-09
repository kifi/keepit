package com.keepit.eliza

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{Model, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json.{Json, JsValue, JsNull, JsObject}
import scala.slick.lifted.Query
import MessagingTypeMappers._

case class Notification(thread: Id[MessageThread], message: Id[Message])

case class UserThreadActivity(id: Id[UserThread], threadId: Id[MessageThread], userId: Id[User], lastActive: Option[DateTime], started: Boolean, lastSeen: Option[DateTime])


case class UserThread(
    id: Option[Id[UserThread]] = None,
    createdAt: DateTime = currentDateTime,
    updateAt: DateTime = currentDateTime,
    user: Id[User],
    thread: Id[MessageThread],
    uriId: Option[Id[NormalizedURI]],
    lastSeen: Option[DateTime],
    unread: Boolean = false,
    muted: Boolean = false,
    lastMsgFromOther: Option[Id[Message]],
    lastNotification: JsValue,
    notificationUpdatedAt: DateTime = currentDateTime,
    notificationLastSeen: Option[DateTime] = None,
    notificationEmailed: Boolean = false,
    replyable: Boolean = true,
    lastActive: Option[DateTime] = None, //Contains the 'createdAt' timestamp of the last message this user sent on this thread
    started: Boolean = false //Wether or not this thread was started by this user
  )
  extends Model[UserThread] {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime)
}


@ImplementedBy(classOf[UserThreadRepoImpl])
trait UserThreadRepo extends Repo[UserThread] {

  def create(user: Id[User], thread: Id[MessageThread], uriIdOpt: Option[Id[NormalizedURI]], started: Boolean=false)(implicit session: RWSession) : UserThread

  def getThreads(user: Id[User], uriId: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]]

  def markRead(user: Id[User], thread: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit

  def markAllReadAtOrBefore(user: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit

  def setNotification(user: Id[User], thread: Id[MessageThread], message: Message, notifJson: JsValue, unread: Boolean)(implicit session: RWSession) : Unit

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification]

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession): Int

  def getLatestSendableNotificationsNotJustFromMe(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsNotJustFromMeBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsNotJustFromMeSince(userId: Id[User], time: DateTime)(implicit session: RSession): Seq[JsObject]

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsSince(userId: Id[User], time: DateTime)(implicit session: RSession): Seq[JsObject]

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getMutedSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestSendableNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestSendableNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], msg: Message)(implicit session: RWSession): Unit

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread]

  def setNotificationEmailed(id: Id[UserThread], relevantMessage: Option[Id[Message]])(implicit session: RWSession) : Unit

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit

  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession) : Unit

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message], newJson: JsValue)(implicit session: RWSession) : Unit

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession) : Seq[UserThread]

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession) : Boolean

  def setNotificationJsonIfNotPresent(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue, message: Message)(implicit session: RWSession) : Unit

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession) : Unit

  def getThreadActivity(theadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity]

}


@Singleton
class UserThreadRepoImpl @Inject() (
    val clock: Clock,
    val db: DataBaseComponent
  )
  extends DbRepo[UserThread] with UserThreadRepo with Logging {

  import db.Driver.Implicit._

  override val table = new RepoTable[UserThread](db, "user_thread") {
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
    def * = id.? ~ createdAt ~ updatedAt ~ user ~ thread ~ uriId.? ~ lastSeen.? ~ unread ~ muted ~ lastMsgFromOther.? ~ lastNotification ~ notificationUpdatedAt ~ notificationLastSeen.? ~ notificationEmailed ~ replyable ~ lastActive.? ~ started <> (UserThread.apply _, UserThread.unapply _)

    def userThreadIndex = index("user_thread", (user,thread), unique=true)
  }

  private def updateSendableNotification(data: JsValue, unread: Boolean): Option[JsObject] = {
    data match {
      case x: JsObject => Some(x.deepMerge(
        if (unread) Json.obj("unread" -> unread) else Json.obj("unread" -> unread, "unreadAuthors" -> 0)
      ))
      case _ => None
    }
  }

  private def updateSendableNotifications(rawNotifications: Seq[(JsValue, Boolean)]): Seq[JsObject] = {
    rawNotifications.map { case (data, unread) =>
      updateSendableNotification(data, unread)
    }.filter(_.isDefined).map(_.get)
  }


  def getThreads(userId: Id[User], uriIdOpt: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]] = {
    uriIdOpt.map{ uriId =>
      (for (row <- table if row.user===userId && row.uriId===uriId) yield row.thread).list
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.thread).list
    }
  }

  def create(user: Id[User], thread: Id[MessageThread], uriIdOpt: Option[Id[NormalizedURI]], started: Boolean = false)(implicit session: RWSession) : UserThread = {
    val userThread = UserThread(
        id=None,
        user=user,
        thread=thread,
        uriId=uriIdOpt,
        lastSeen=None,
        lastMsgFromOther=None,
        lastNotification=JsNull,
        started=started
      )
    save(userThread)
  }

  def markRead(user: Id[User], threadOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit = {
    threadOpt.map{ thread =>
      (for (row <- table if row.user === user && row.thread === thread) yield row.unread ~ row.updatedAt).update((false, clock.now()))
    } getOrElse {
      (for (row <- table if row.user === user) yield row.unread ~ row.updatedAt).update((false, clock.now()))
    }
  }

  def markAllReadAtOrBefore(userId: Id[User], timeCutoff: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user === userId && row.notificationUpdatedAt <= timeCutoff) yield row.unread ~ row.updatedAt).update((false, clock.now()))
  }

  def setNotification(userId: Id[User], threadId: Id[MessageThread], message: Message, notifJson: JsValue, unread: Boolean)(implicit session: RWSession) : Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther < message.id.get))
      .map(row => row.lastNotification ~ row.lastMsgFromOther ~ row.unread ~ row.notificationUpdatedAt ~ row.notificationEmailed ~ row.updatedAt)
      .update((notifJson, message.id.get, unread, message.createdAt, false, clock.now()))

    Query(table).filter(row => (row.user===userId && row.thread===threadId) && row.lastMsgFromOther === message.id.get)
      .map(row => row.lastNotification ~ row.notificationEmailed ~ row.updatedAt)
      .update((notifJson, false, clock.now()))
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit = {  //Note: minor race condition
    (for (row <- table if row.user===userId && row.thread===threadId && (row.lastSeen < timestamp || row.lastSeen.isNull)) yield row.lastSeen ~ row.updatedAt).update((timestamp, clock.now()))
  }

  def getUnreadThreadNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification] = {
    (for (row <- table if row.user===userId && row.unread) yield (row.thread, row.lastMsgFromOther.?)).list.map{
     case (thread, message) =>
        Notification(thread, message.get)
    }
  }

  def setMuteState(userThreadId: Id[UserThread], muted: Boolean)(implicit session: RWSession) = {
    (for (row <- table if row.id === userThreadId) yield row.muted ~ row.updatedAt).update((muted, clock.now()))
  }

  def getLatestSendableNotificationsNotJustFromMe(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsNotJustFromMeBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.notificationUpdatedAt < time &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsNotJustFromMeSince(userId: Id[User], time: DateTime)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.notificationUpdatedAt > time &&
                            row.lastMsgFromOther.isNotNull &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsSince(userId: Id[User], time: DateTime)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.notificationUpdatedAt > time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.unread &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.unread &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.muted &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getMutedSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.muted &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestSendableNotificationsForStartedThreads(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
     (for (row <- table if row.user === userId &&
                           row.started &&
                           row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                           row.lastNotification.isNotNull) yield row)
     .sortBy(row => (row.notificationUpdatedAt) desc)
     .take(howMany).map(row => row.lastNotification ~ row.unread)
     .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsForStartedThreadsBefore(userId: Id[User], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.started &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestSendableNotificationsForUri(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.uriId === uriId &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsForUriBefore(userId: Id[User], uriId: Id[NormalizedURI], time: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications =
      (for (row <- table if row.user === userId &&
                            row.uriId === uriId &&
                            row.notificationUpdatedAt < time &&
                            row.lastNotification =!= JsNull.asInstanceOf[JsValue] &&
                            row.lastNotification.isNotNull) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.unread)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getUnreadUnmutedThreadCount(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (row <- table if row.user === userId && row.unread && !row.muted) yield row).length).first
  }

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread = {
    (for (row <- table if row.user === userId && row.thread === threadId) yield row).first
  }

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], message: Message)(implicit session: RWSession): Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther <= message.id.get))
      .map(row => row.lastMsgFromOther ~ row.unread ~ row.notificationUpdatedAt ~ row.updatedAt)
      .update((message.id.get, false, message.createdAt, clock.now()))
  }

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread] = {
    (for (row <- table if row.replyable && row.unread && !row.notificationEmailed && row.notificationUpdatedAt < before) yield row).list
  }

  def setNotificationEmailed(id: Id[UserThread], relevantMessageOpt: Option[Id[Message]])(implicit session: RWSession) : Unit = {
    relevantMessageOpt.map{ relevantMessage =>
      (for (row <- table if row.id===id && row.lastMsgFromOther===relevantMessage) yield row.notificationEmailed).update(true)
    } getOrElse {
      (for (row <- table if row.id===id) yield row.notificationEmailed ~ row.updatedAt).update((true, clock.now()))
    }
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.uriId===oldId) yield row.uriId).update(newId)
    }
  }

  def markUnread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user === userId && row.thread === threadId) yield row.unread ~ row.updatedAt).update((true, clock.now()))
  }

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message], newJson: JsValue)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId &&row.thread===threadId && row.lastMsgFromOther===messageId) yield row.lastNotification ~ row.updatedAt).update((newJson, clock.now()))
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession) : Seq[UserThread] = {
    (for (row <- table if row.uriId===uriId) yield row).list
  }

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Boolean = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.muted).firstOption.getOrElse(false)
  }

  def setNotificationJsonIfNotPresent(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue, message: Message)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId && row.lastMsgFromOther.isNull) yield row.lastNotification ~ row.notificationUpdatedAt).update((notifJson, message.createdAt))
  }

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.lastActive ~ row.updatedAt).update((lastActive, clock.now()))
  }

  def getThreadActivity(threadId: Id[MessageThread])(implicit session: RSession): Seq[UserThreadActivity] = {
    val rawData = (for (row <- table if row.thread===threadId) yield row.id ~ row.thread ~ row.user ~ row.lastActive.? ~ row.started ~ row.lastSeen.?).list
    rawData.map{tuple => (UserThreadActivity.apply _).tupled(tuple) }
  }

}

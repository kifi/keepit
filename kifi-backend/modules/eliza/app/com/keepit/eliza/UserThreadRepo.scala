package com.keepit.eliza

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, ExternalIdColumnFunction, ExternalIdColumnDbFunction, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time.{currentDateTime, zones, Clock}
import com.keepit.common.db.{Model, Id, ExternalId}
import com.keepit.model.{User, NormalizedURI}
import play.api.libs.json.{Json, JsValue, JsNull}
import scala.slick.lifted.Query
import MessagingTypeMappers._

case class Notification(thread: Id[MessageThread], message: Id[Message])


case class UserThread(
    id: Option[Id[UserThread]],
    createdAt: DateTime = currentDateTime(zones.PT), 
    updateAt: DateTime = currentDateTime(zones.PT), 
    user: Id[User],
    thread: Id[MessageThread],
    uriId: Option[Id[NormalizedURI]],
    lastSeen: Option[DateTime],
    notificationPending: Boolean = false,
    muted: Boolean = false,
    lastMsgFromOther: Option[Id[Message]],
    lastNotification: JsValue,
    notificationUpdatedAt: DateTime = currentDateTime(zones.PT),
    notificationLastSeen: Option[DateTime] = None
  ) 
  extends Model[UserThread] {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime) 
}


@ImplementedBy(classOf[UserThreadRepoImpl])
trait UserThreadRepo extends Repo[UserThread] {

  def create(user: Id[User], thread: Id[MessageThread], uriIdOpt: Option[Id[NormalizedURI]])(implicit session: RWSession) : UserThread

  def getThreads(user: Id[User], uriId: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]]

  def clearNotification(user: Id[User], thread: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit

  def clearNotificationsBefore(user: Id[User], timeCutoff: DateTime)(implicit session: RWSession): Unit

  def setNotification(user: Id[User], thread: Id[MessageThread], notifJson: JsValue)(implicit session: RWSession) : Unit

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit

  def getPendingNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification]

  def setLastMsgFromOther(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message])(implicit session: RWSession) : Unit

  def setNotificationLastSeen(userId: Id[User], timestamp: DateTime, threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit

  def getNotificationLastSeen(userId: Id[User], threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RSession): Option[DateTime]

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsValue] 

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsValue]

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsValue]

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread

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
    def notificationPending = column[Boolean]("notification_pending", O.NotNull)
    def muted = column[Boolean]("muted", O.NotNull)
    def lastMsgFromOther = column[Id[Message]]("last_msg_from_other", O.Nullable)
    def lastNotification = column[JsValue]("last_notification", O.NotNull)
    def notificationUpdatedAt = column[DateTime]("notification_updated_at", O.NotNull)
    def notificationLastSeen = column[DateTime]("notification_last_seen", O.Nullable)
    def * = id.? ~ createdAt ~ updatedAt ~ user ~ thread ~ uriId.? ~ lastSeen.? ~ notificationPending ~ muted ~ lastMsgFromOther.? ~ lastNotification ~ notificationUpdatedAt ~ notificationLastSeen.? <> (UserThread.apply _, UserThread.unapply _)

    def userThreadIndex = index("user_thread", (user,thread), unique=true)
  }

  def getThreads(userId: Id[User], uriIdOpt: Option[Id[NormalizedURI]]=None)(implicit session: RSession) : Seq[Id[MessageThread]] = {
    uriIdOpt.map{ uriId =>
      (for (row <- table if row.user===userId && row.uriId===uriId) yield row.thread).list
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.thread).list
    }
  }

  def create(user: Id[User], thread: Id[MessageThread], uriIdOpt: Option[Id[NormalizedURI]])(implicit session: RWSession) : UserThread = {
    val userThread = UserThread(
        id=None,
        user=user,
        thread=thread,
        uriId=uriIdOpt,
        lastSeen=None,
        lastMsgFromOther=None,
        lastNotification=JsNull
      )
    save(userThread)
  }

  def clearNotification(user: Id[User], threadOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit = {
    threadOpt.map{ thread =>
      (for (row <- table if row.user===user && row.thread===thread) yield row.notificationPending).update(false)
    } getOrElse {
      (for (row <- table if row.user===user) yield row.notificationPending).update(false)
    }
  }

  def clearNotificationsBefore(userId: Id[User], timeCutoff: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.notificationUpdatedAt<=timeCutoff) yield row.notificationPending).update(false)
  }

  def setNotification(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield (row.lastNotification)).update(notifJson)
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationPending).update(true)
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationUpdatedAt).update(currentDateTime(zones.PT))
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.lastSeen).update(timestamp)
  }

  def getPendingNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification] = {
    (for (row <- table if row.user===userId && row.notificationPending===true) yield (row.thread, row.lastMsgFromOther.?)).list.map{
     case (thread, message) =>
        Notification(thread, message.get)
    }
  }

  def setLastMsgFromOther(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message])(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.lastMsgFromOther).update(messageId)
  }

  def setNotificationLastSeen(userId: Id[User], timestamp: DateTime, threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession): Unit = {
    threadIdOpt.map{ threadId =>
      (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationLastSeen).update(timestamp)
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.notificationLastSeen).update(timestamp)
    }
  }

  def getNotificationLastSeen(userId: Id[User], threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RSession): Option[DateTime] = {
    threadIdOpt.map{ threadId =>
      (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationLastSeen).firstOption
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.notificationLastSeen).firstOption
    }
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsValue] = {
    (for (row <- table if row.user === userId) yield row)
      .sortBy(row => (row.createdAt) desc)
      .take(howMany).map(_.lastNotification).list
  }

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (row <- table if row.user===userId && row.notificationPending===true) yield row).length).first
  }

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsValue] = {
    (for (row <- table if row.user===userId && row.notificationUpdatedAt > after) yield row)
    .sortBy(row => (row.notificationUpdatedAt) desc)
    .map(_.lastNotification) 
    .list
  }

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsValue] = {
    (for (row <- table if row.user===userId && row.notificationUpdatedAt < before) yield row)
    .sortBy(row => (row.notificationUpdatedAt) desc)
    .map(_.lastNotification)
    .take(howMany) 
    .list
  }

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row).first
  }

}

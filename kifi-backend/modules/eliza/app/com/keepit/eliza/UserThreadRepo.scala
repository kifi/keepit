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
import play.api.libs.json.{Json, JsValue, JsNull, JsObject}
import scala.slick.lifted.Query
import MessagingTypeMappers._

case class Notification(thread: Id[MessageThread], message: Id[Message])


case class UserThread(
    id: Option[Id[UserThread]] = None,
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
    notificationLastSeen: Option[DateTime] = None,
    notoficationEmailed: Boolean = false,
    replyable: Boolean = true
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

  def setNotification(user: Id[User], thread: Id[MessageThread], message: Message, notifJson: JsValue)(implicit session: RWSession) : Unit

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit

  def getPendingNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification]

  def setNotificationLastSeen(userId: Id[User], timestamp: DateTime, threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit

  def getNotificationLastSeen(userId: Id[User], threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RSession): Option[DateTime]

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] 

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], msg: Message)(implicit session: RWSession): Unit

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread]

  def setNotificationEmailed(id: Id[UserThread], relevantMessage: Option[Id[Message]])(implicit session: RWSession) : Unit

  def updateUriIds(updates: Map[Id[NormalizedURI], Id[NormalizedURI]])(implicit session: RWSession) : Unit

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
    def notificationEmailed = column[Boolean]("notification_emailed", O.NotNull)
    def replyable = column[Boolean]("replyable", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ user ~ thread ~ uriId.? ~ lastSeen.? ~ notificationPending ~ muted ~ lastMsgFromOther.? ~ lastNotification ~ notificationUpdatedAt ~ notificationLastSeen.? ~ notificationEmailed ~ replyable <> (UserThread.apply _, UserThread.unapply _)

    def userThreadIndex = index("user_thread", (user,thread), unique=true)
  }

  private def updateSendableNotification(data: JsValue, pending: Boolean): Option[JsObject] = {
    data match {
      case x:JsObject => Some(x.deepMerge(Json.obj("unread"->pending)))
      case _ => None
    }
  }

  private def updateSendableNotifications(rawNotifications: Seq[(JsValue, Boolean)]): Seq[JsObject] = {
    rawNotifications.map{ data_pending => 
      val (data, pending) : (JsValue, Boolean) = data_pending
      updateSendableNotification(data, pending)
    }.filter(_.isDefined).map(_.get)
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

  def setNotification(userId: Id[User], threadId: Id[MessageThread], message: Message, notifJson: JsValue)(implicit session: RWSession) : Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther < message.id.get))
      .map(row => row.lastNotification ~ row.lastMsgFromOther ~ row.notificationPending ~ row.notificationUpdatedAt ~ row.notificationEmailed)
      .update((notifJson, message.id.get, true, message.createdAt, false))

    Query(table).filter(row => (row.user===userId && row.thread===threadId) && row.lastMsgFromOther === message.id.get)
      .map(row => row.lastNotification ~ row.notificationEmailed)
      .update((notifJson, false))
  }

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit = {  //Note: minor race condition
    (for (row <- table if row.user===userId && row.thread===threadId && (row.lastSeen < timestamp || row.lastSeen.isNull)) yield row.lastSeen).update(timestamp)
  }

  def getPendingNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification] = {
    (for (row <- table if row.user===userId && row.notificationPending===true) yield (row.thread, row.lastMsgFromOther.?)).list.map{
     case (thread, message) =>
        Notification(thread, message.get)
    }
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
      (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationLastSeen.?).firstOption flatten
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.notificationLastSeen.?).firstOption flatten
    }
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user === userId && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
                            .sortBy(row => (row.notificationUpdatedAt) desc)
                            .take(howMany).map(row => row.lastNotification ~ row.notificationPending)
                            .list
    updateSendableNotifications(rawNotifications)
  }

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (row <- table if row.user===userId && row.notificationPending===true) yield row).length).first
  }

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user===userId && row.notificationUpdatedAt > after && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
                            .sortBy(row => (row.notificationUpdatedAt) desc)
                            .map(row => row.lastNotification ~ row.notificationPending) 
                            .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user===userId && row.notificationUpdatedAt < before && row.lastNotification=!=JsNull.asInstanceOf[JsValue] &&row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
                            .sortBy(row => (row.notificationUpdatedAt) desc)
                            .map(row => row.lastNotification ~ row.notificationPending)
                            .take(howMany) 
                            .list
    updateSendableNotifications(rawNotifications)
  }

  def getUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): UserThread = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row).first
  }

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], message: Message)(implicit session: RWSession): Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther <= message.id.get))
      .map(row => row.lastMsgFromOther ~ row.notificationPending ~ row.notificationUpdatedAt)
      .update((message.id.get, false, message.createdAt))
  }

  def getUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[UserThread] = {
    (for (row <- table if row.replyable===true && row.notificationPending===true && row.notificationEmailed===false && (row.notificationLastSeen.isNull || row.notificationLastSeen < row.notificationUpdatedAt) && row.notificationUpdatedAt < before) yield row).list
  }

  def setNotificationEmailed(id: Id[UserThread], relevantMessageOpt: Option[Id[Message]])(implicit session: RWSession) : Unit = {
    relevantMessageOpt.map{ relevantMessage =>
      (for (row <- table if row.id===id && row.lastMsgFromOther===relevantMessage) yield row.notificationEmailed).update(true)
    } getOrElse {
      (for (row <- table if row.id===id) yield row.notificationEmailed).update(true)
    }
  }

  def updateUriIds(updates: Map[Id[NormalizedURI], Id[NormalizedURI]])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.uriId===oldId) yield row.uriId).update(newId)
    } 
  }


}

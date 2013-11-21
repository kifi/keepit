package com.keepit.eliza.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.{Repo, DbRepo, DataBaseComponent}
import com.keepit.common.db.slick.FortyTwoTypeMappers._
import com.keepit.common.db.slick.DBSession.{RWSession, RSession}
import org.joda.time.DateTime
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.common.db.{Model, Id}
import com.keepit.model.{EContact, User, NormalizedURI}
import play.api.libs.json._
import scala.slick.lifted.Query
import com.keepit.eliza._
import play.api.libs.functional.syntax._
import scala.Some
import com.keepit.eliza.Notification
import play.api.libs.json.JsObject

case class NonUserKind(name: String)

case class NonUserThread(
  id: Option[Id[NonUserThread]] = None,
  createdAt: DateTime = currentDateTime,
  updateAt: DateTime = currentDateTime,
  thread: Id[MessageThread],
  uriId: Option[Id[NormalizedURI]],
  notifiedCount: Int = 0,
  muted: Boolean = false,
  kind: NonUserKind,
  emailAddress: Option[String],
  econtactId: Option[Id[EContact]]
) extends Model[NonUserThread] {
  def withId(id: Id[NonUserThread]): NonUserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt=updateTime)
}

case class BasicNonUser(`type`: NonUserKind, id: String, firstName: Option[String], lastName: Option[String])

object BasicNonUser {
  implicit val nonUserTypeFormat = Json.format[NonUserKind]
  implicit val basicNonUserFormat = (
    (__ \ 'type).format[NonUserKind] and
      (__ \ 'id).format[String] and
      (__ \ 'firstName).formatNullable[String] and
      (__ \ 'lastName).formatNullable[String]
    )(BasicNonUser.apply, unlift(BasicNonUser.unapply))

}


@ImplementedBy(classOf[NonUserThreadRepoImpl])
trait NonUserThreadRepo extends Repo[NonUserThread] {

  def getThreads(kind: NonUserKind, emailAddress: Option[String] = None, econtactId: Option[Id[EContact]] = None)(implicit session: RSession) : Seq[Id[MessageThread]]

  def setNotification(user: Id[User], thread: Id[MessageThread], message: Message, notifJson: JsValue, pending: Boolean)(implicit session: RWSession) : Unit

  def setLastSeen(userId: Id[User], threadId: Id[MessageThread], timestamp: DateTime)(implicit session: RWSession) : Unit

  def getPendingNotifications(userId: Id[User])(implicit session: RSession) : Seq[Notification]

  def setNotificationLastSeen(userId: Id[User], timestamp: DateTime, threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RWSession) : Unit

  def getNotificationLastSeen(userId: Id[User], threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RSession): Option[DateTime]

  def setMuteState(NonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession): Int

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getLatestSendableNotificationsForThreads(userId: Id[User], activeThreads: Seq[Id[MessageThread]], howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsObject]

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject]

  def getNonUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): NonUserThread

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], msg: Message)(implicit session: RWSession): Unit

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[NonUserThread]

  def setNotificationEmailed(id: Id[NonUserThread], relevantMessage: Option[Id[Message]])(implicit session: RWSession) : Unit

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession) : Boolean

}


@Singleton
class NonUserThreadRepoImpl @Inject() (
  val clock: Clock,
  val db: DataBaseComponent
  )
  extends DbRepo[NonUserThread] with NonUserThreadRepo with Logging {

  import db.Driver.Implicit._

  override val table = new RepoTable[NonUserThread](db, "user_thread") {
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
    def lastActive = column[DateTime]("last_active", O.Nullable)
    def started = column[Boolean]("started", O.NotNull)
    def * = id.? ~ createdAt ~ updatedAt ~ user ~ thread ~ uriId.? ~ lastSeen.? ~ notificationPending ~ muted ~ lastMsgFromOther.? ~ lastNotification ~ notificationUpdatedAt ~ notificationLastSeen.? ~ notificationEmailed ~ replyable ~ lastActive.? ~ started <> (NonUserThread.apply _, NonUserThread.unapply _)

    def NonUserThreadIndex = index("user_thread", (user,thread), unique=true)
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

  def setNotification(userId: Id[User], threadId: Id[MessageThread], message: Message, notifJson: JsValue, pending: Boolean)(implicit session: RWSession) : Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther < message.id.get))
      .map(row => row.lastNotification ~ row.lastMsgFromOther ~ row.notificationPending ~ row.notificationUpdatedAt ~ row.notificationEmailed)
      .update((notifJson, message.id.get, pending, message.createdAt, false))

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

  def setMuteState(NonUserThreadId: Id[NonUserThread], muted: Boolean)(implicit session: RWSession) = {
    (for (row <- table if row.id === NonUserThreadId) yield row.muted).update(muted)
  }

  def getNotificationLastSeen(userId: Id[User], threadIdOpt: Option[Id[MessageThread]]=None)(implicit session: RSession): Option[DateTime] = {
    threadIdOpt.map{ threadId =>
      (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationLastSeen.?).firstOption flatten
    } getOrElse {
      (for (row <- table if row.user===userId) yield row.notificationLastSeen.?).firstOption flatten
    }
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user === userId && !row.lastMsgFromOther.isNull && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.notificationPending)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user === userId && row.notificationPending===true) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.notificationPending)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestMutedSendableNotifications(userId: Id[User], howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user === userId && row.muted===true && !row.lastMsgFromOther.isNull && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.notificationPending)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getLatestSendableNotificationsForThreads(userId: Id[User], activeThreads: Seq[Id[MessageThread]], howMany: Int)(implicit session: RSession): Seq[JsObject] = { //Not very efficinet - Stephen
  val rawNotifications = (for (row <- table if row.user === userId && row.thread.inSet(activeThreads.toSet) && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .take(howMany).map(row => row.lastNotification ~ row.notificationPending)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getPendingNotificationCount(userId: Id[User])(implicit session: RSession): Int = {
    Query((for (row <- table if row.user===userId && row.notificationPending===true) yield row).length).first
  }

  def getSendableNotificationsAfter(userId: Id[User], after: DateTime)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user===userId && row.notificationUpdatedAt > after && !row.lastMsgFromOther.isNull && row.lastNotification=!=JsNull.asInstanceOf[JsValue] && row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => row.lastNotification ~ row.notificationPending)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getSendableNotificationsBefore(userId: Id[User], before: DateTime, howMany: Int)(implicit session: RSession): Seq[JsObject] = {
    val rawNotifications = (for (row <- table if row.user===userId && row.notificationUpdatedAt < before && !row.lastMsgFromOther.isNull && row.lastNotification=!=JsNull.asInstanceOf[JsValue] &&row.lastNotification=!=null.asInstanceOf[JsValue]) yield row)
      .sortBy(row => (row.notificationUpdatedAt) desc)
      .map(row => row.lastNotification ~ row.notificationPending)
      .take(howMany)
      .list
    updateSendableNotifications(rawNotifications)
  }

  def getNonUserThread(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): NonUserThread = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row).first
  }

  def clearNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], message: Message)(implicit session: RWSession): Unit = {
    Query(table).filter(row => (row.user===userId && row.thread===threadId) && (row.lastMsgFromOther.isNull || row.lastMsgFromOther <= message.id.get))
      .map(row => row.lastMsgFromOther ~ row.notificationPending ~ row.notificationUpdatedAt)
      .update((message.id.get, false, message.createdAt))
  }

  def getNonUserThreadsForEmailing(before: DateTime)(implicit session: RSession) : Seq[NonUserThread] = {
    (for (row <- table if row.replyable===true && row.notificationPending===true && row.notificationEmailed===false && (row.notificationLastSeen.isNull || row.notificationLastSeen < row.notificationUpdatedAt) && row.notificationUpdatedAt < before) yield row).list
  }

  def setNotificationEmailed(id: Id[NonUserThread], relevantMessageOpt: Option[Id[Message]])(implicit session: RWSession) : Unit = {
    relevantMessageOpt.map{ relevantMessage =>
      (for (row <- table if row.id===id && row.lastMsgFromOther===relevantMessage) yield row.notificationEmailed).update(true)
    } getOrElse {
      (for (row <- table if row.id===id) yield row.notificationEmailed).update(true)
    }
  }

  def updateUriIds(updates: Seq[(Id[NormalizedURI], Id[NormalizedURI])])(implicit session: RWSession) : Unit = {
    updates.foreach{ case (oldId, newId) =>
      (for (row <- table if row.uriId===oldId) yield row.uriId).update(newId)
    }
  }

  def markPending(userId: Id[User], threadId: Id[MessageThread])(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.notificationPending).update(true)
  }

  def updateLastNotificationForMessage(userId: Id[User], threadId: Id[MessageThread], messageId: Id[Message], newJson: JsValue)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId &&row.thread===threadId && row.lastMsgFromOther===messageId) yield row.lastNotification).update(newJson)
  }

  def getByUriId(uriId: Id[NormalizedURI])(implicit session: RSession) : Seq[NonUserThread] = {
    (for (row <- table if row.uriId===uriId) yield row).list
  }

  def isMuted(userId: Id[User], threadId: Id[MessageThread])(implicit session: RSession): Boolean = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.muted).firstOption.getOrElse(false)
  }

  def setNotificationJsonIfNotPresent(userId: Id[User], threadId: Id[MessageThread], notifJson: JsValue, message: Message)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId && row.lastMsgFromOther.isNull) yield row.lastNotification ~ row.notificationUpdatedAt).update((notifJson, message.createdAt))
  }

  def setLastActive(userId: Id[User], threadId: Id[MessageThread], lastActive: DateTime)(implicit session: RWSession) : Unit = {
    (for (row <- table if row.user===userId && row.thread===threadId) yield row.lastActive).update(lastActive)
  }


}

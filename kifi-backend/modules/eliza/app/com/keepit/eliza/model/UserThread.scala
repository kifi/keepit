package com.keepit.eliza.model

import com.keepit.common.time._
import com.keepit.common.db.{Model, Id}
import com.keepit.model.{User, NormalizedURI}

import play.api.libs.json._

import org.joda.time.DateTime

import scala.Some
import com.keepit.common.crypto.ModelWithPublicId

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
    started: Boolean = false //Whether or not this thread was started by this user
  )
  extends Model[UserThread] with ModelWithPublicId[UserThread] {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updateAt = updateTime)

  lazy val summary = s"UserThread[id = $id, created = $createdAt, update = $updateAt, user = $user, thread = $thread, " +
    s"uriId = $uriId, lastSeen = $lastSeen, unread = $unread, notificationUpdatedAt = $notificationUpdatedAt, " +
    s"notificationLastSeen = $notificationLastSeen, notificationEmailed = $notificationEmailed, replyable = $replyable]"
}

object UserThread {
  implicit object userThread extends ModelWithPublicId[UserThread] {
    override val prefix = "us"
  }
}


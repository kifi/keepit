package com.keepit.eliza.model

import com.keepit.common.time._
import com.keepit.common.db.{ State, States, Model, Id }
import com.keepit.eliza.model.UserThreadRepo.RawNotification
import com.keepit.model.{ User, NormalizedURI }

import play.api.libs.json._

import org.joda.time.DateTime

import scala.Some
import com.keepit.common.crypto.ModelWithPublicId

case class UserThreadNotification(thread: Id[MessageThread], message: Id[ElizaMessage])

case class UserThreadActivity(id: Id[UserThread], threadId: Id[MessageThread], userId: Id[User], lastActive: Option[DateTime], started: Boolean, lastSeen: Option[DateTime])

case class UserThread(
  id: Option[Id[UserThread]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[UserThread] = UserThreadStates.ACTIVE,
  user: Id[User],
  threadId: Id[MessageThread],
  uriId: Option[Id[NormalizedURI]],
  lastSeen: Option[DateTime],
  unread: Boolean = false,
  muted: Boolean = false,
  lastMsgFromOther: Option[Id[ElizaMessage]],
  lastNotification: JsValue, // Option[JsObject] would have been a better choice (using database null instead of 'null')
  notificationUpdatedAt: DateTime = currentDateTime,
  notificationLastSeen: Option[DateTime] = None,
  notificationEmailed: Boolean = false,
  lastActive: Option[DateTime] = None, //Contains the 'createdAt' timestamp of the last message this user sent on this thread
  startedBy: Id[User], // denormalized from MessageThread
  accessToken: ThreadAccessToken = ThreadAccessToken())
    extends Model[UserThread] with ParticipantThread {

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def sanitizeForDelete = this.copy(state = UserThreadStates.INACTIVE)

  lazy val summary = s"UserThread[id = $id, created = $createdAt, update = $updatedAt, user = $user, thread = $threadId, " +
    s"uriId = $uriId, lastSeen = $lastSeen, unread = $unread, notificationUpdatedAt = $notificationUpdatedAt, " +
    s"notificationLastSeen = $notificationLastSeen, notificationEmailed = $notificationEmailed]"

  def toRawNotification: RawNotification = (lastNotification, unread, uriId)
}

object UserThreadStates extends States[UserThread]

object UserThread {
  def forMessageThread(mt: MessageThread)(user: Id[User]) = UserThread(
    user = user,
    threadId = mt.id.get,
    uriId = Some(mt.uriId),
    lastSeen = None,
    lastMsgFromOther = None,
    lastNotification = JsNull,
    startedBy = mt.startedBy
  )
  def toUserThreadView(userThread: UserThread, messages: Seq[ElizaMessage], messageThread: MessageThread): UserThreadView = {
    UserThreadView(
      pageTitle = messageThread.pageTitle,
      uriId = userThread.uriId,
      lastSeen = userThread.lastSeen,
      notificationUpdatedAt = userThread.notificationUpdatedAt,
      messages = messages map ElizaMessage.toMessageView
    )
  }
}

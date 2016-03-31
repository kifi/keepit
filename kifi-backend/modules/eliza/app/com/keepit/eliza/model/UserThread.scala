package com.keepit.eliza.model

import com.keepit.common.time._
import com.keepit.common.db._
import com.keepit.model.{ Keep, User, NormalizedURI }

import play.api.libs.json._

import org.joda.time.DateTime

import scala.Some
import com.keepit.common.crypto.ModelWithPublicId

case class UserThreadNotification(keepId: Id[Keep], message: Id[ElizaMessage])

case class UserThreadActivity(id: Id[UserThread], keepId: Id[Keep], userId: Id[User], lastActive: Option[DateTime], started: Boolean, lastSeen: Option[DateTime])

case class UserThread(
  id: Option[Id[UserThread]] = None,
  createdAt: DateTime = currentDateTime,
  updatedAt: DateTime = currentDateTime,
  state: State[UserThread] = UserThreadStates.ACTIVE,
  user: Id[User],
  keepId: Id[Keep],
  uriId: Option[Id[NormalizedURI]],
  lastSeen: Option[DateTime],
  unread: Boolean = false,
  muted: Boolean = false,
  latestMessageId: Option[Id[ElizaMessage]],
  notificationUpdatedAt: DateTime = currentDateTime,
  notificationEmailed: Boolean = false,
  lastActive: Option[DateTime] = None, //Contains the 'createdAt' timestamp of the last message this user sent on this thread
  startedBy: Id[User], // denormalized from MessageThread
  accessToken: ThreadAccessToken = ThreadAccessToken())
    extends Model[UserThread] with ModelWithState[UserThread] with ParticipantThread {
  def isActive = state == UserThreadStates.ACTIVE

  def withId(id: Id[UserThread]): UserThread = this.copy(id = Some(id))
  def withUpdateTime(updateTime: DateTime) = this.copy(updatedAt = updateTime)
  def sanitizeForDelete = this.copy(state = UserThreadStates.INACTIVE)

  lazy val summary = s"UserThread[id = $id, created = $createdAt, update = $updatedAt, user = $user, keep = $keepId, " +
    s"uriId = $uriId, lastSeen = $lastSeen, unread = $unread, notificationUpdatedAt = $notificationUpdatedAt, " +
    s"notificationEmailed = $notificationEmailed]"
}

object UserThreadStates extends States[UserThread]

object UserThread {
  def forMessageThread(mt: MessageThread)(user: Id[User]) = UserThread(
    user = user,
    keepId = mt.keepId,
    uriId = Some(mt.uriId),
    lastSeen = None,
    unread = user != mt.startedBy,
    latestMessageId = None,
    startedBy = mt.startedBy
  )
  def fromNonUserThread(nut: NonUserThread, user: Id[User]) = UserThread(
    user = user,
    keepId = nut.keepId,
    uriId = nut.uriId,
    lastSeen = Some(currentDateTime),
    unread = false,
    muted = nut.muted,
    latestMessageId = None,
    startedBy = nut.createdBy
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

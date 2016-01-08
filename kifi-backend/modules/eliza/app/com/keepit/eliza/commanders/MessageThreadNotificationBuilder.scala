package com.keepit.eliza.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.discussion.Message
import com.keepit.eliza.model._
import com.keepit.model.{ User, NotificationCategory, DeepLocator, Keep }
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

// This is the thing we send to clients so they can display the little
// "notification" card that summarizes a message thread
case class MessageThreadNotification(
  // Info about the most recent message
  id: PublicId[Message],
  time: DateTime,
  author: Option[BasicUserLikeEntity],
  text: String,
  // Information about the thread
  threadId: PublicId[Keep],
  locator: DeepLocator,
  url: String,
  title: Option[String],
  participants: Seq[BasicUserLikeEntity],
  // user-specific information
  unread: Boolean,
  muted: Boolean,
  // stuff that we send to help clients display
  category: NotificationCategory,
  firstAuthor: Int,
  numAuthors: Int,
  numUnseenAuthors: Int,
  numMessages: Int,
  numUnreadMessages: Int)
object MessageThreadNotification {
  // TODO(ryan): pray for forgiveness for this travesty
  def apply(message: ElizaMessage, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser,
    unread: Boolean, originalAuthorIdx: Int, numUnseenAuthors: Int, numAuthors: Int,
    numMessages: Int, numUnread: Int, muted: Boolean)(implicit publicIdConfig: PublicIdConfiguration): MessageThreadNotification = MessageThreadNotification(
    id = message.pubId,
    time = message.createdAt,
    author = messageWithBasicUser.user,
    text = message.messageText,
    threadId = thread.pubKeepId,
    locator = thread.deepLocator,
    url = message.sentOnUrl.getOrElse(thread.url),
    title = thread.pageTitle,
    participants = messageWithBasicUser.participants.sortBy(x => x.fold(nu => (nu.firstName.getOrElse(""), nu.lastName.getOrElse("")), u => (u.firstName, u.lastName))),
    unread = unread,
    muted = muted,
    category = NotificationCategory.User.MESSAGE,
    firstAuthor = originalAuthorIdx,
    numAuthors = numAuthors,
    numUnseenAuthors = numUnseenAuthors,
    numMessages = numMessages,
    numUnreadMessages = numUnread
  )
  implicit def writes: Writes[MessageThreadNotification] = (
    (__ \ 'id).write[PublicId[Message]] and
    (__ \ 'time).write[DateTime] and
    (__ \ 'author).writeNullable[BasicUserLikeEntity] and
    (__ \ 'text).write[String] and
    (__ \ 'thread).write[PublicId[Keep]] and
    (__ \ 'locator).write[DeepLocator] and
    (__ \ 'url).write[String] and
    (__ \ 'title).writeNullable[String] and
    (__ \ 'participants).write[Seq[BasicUserLikeEntity]] and
    (__ \ 'unread).write[Boolean] and
    (__ \ 'muted).write[Boolean] and
    (__ \ 'category).write[NotificationCategory] and
    (__ \ 'firstAuthor).write[Int] and
    (__ \ 'authors).write[Int] and
    (__ \ 'unreadAuthors).write[Int] and
    (__ \ 'messages).write[Int] and
    (__ \ 'unreadMessages).write[Int]
  )(unlift(MessageThreadNotification.unapply))
}

@ImplementedBy(classOf[MessageThreadNotificationBuilderImpl])
trait MessageThreadNotificationBuilder {
  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], MessageThreadNotification]
  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], MessageThreadNotification]
}

@Singleton
class MessageThreadNotificationBuilderImpl @Inject() (
  messageThreadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  messageRepo: MessageRepo)
    extends MessageThreadNotificationBuilder {
  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]])(implicit session: RSession): Map[Id[Keep], MessageThreadNotification] = ???
  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]])(implicit session: RSession): Map[Id[User], MessageThreadNotification] = ???
}


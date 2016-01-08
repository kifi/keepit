package com.keepit.eliza.commanders

import com.google.inject.{ Inject, ImplementedBy, Singleton }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.model._
import com.keepit.model.{ User, NotificationCategory, DeepLocator, Keep }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ Future, ExecutionContext }

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
  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], MessageThreadNotification]]
  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]]): Future[Map[Id[User], MessageThreadNotification]]
}

object MessageThreadNotificationBuilder {
  object PrecomputedInfo {
    case class BuildForKeeps(dummy: Int)
    case class BuildForUsers(dummy: Int)
  }
}

@Singleton
class MessageThreadNotificationBuilderImpl @Inject() (
  db: Database,
  messageThreadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  messageRepo: MessageRepo,
  shoebox: ShoeboxServiceClient,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val executionContext: ExecutionContext)
    extends MessageThreadNotificationBuilder {

  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]]): Future[Map[Id[Keep], MessageThreadNotification]] = {
    val infoFut = db.readOnlyMasterAsync { implicit s =>
      val threadsById = messageThreadRepo.getByKeepIds(keepIds)
      val lastMsgById = keepIds.map { keepId => keepId -> messageRepo.getLatest(keepId) }.toMap
      val mutedById = keepIds.map { keepId => keepId -> userThreadRepo.isMuted(userId, keepId) }.toMap
      val threadActivityById = keepIds.map { keepId =>
        keepId -> userThreadRepo.getThreadActivity(keepId).sortBy { uta =>
          (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
        }
      }.toMap
      val msgCountsById = threadActivityById.map {
        case (keepId, activity) =>
          val lastSeenOpt = activity.find(_.userId == userId).flatMap(_.lastSeen)
          keepId -> messageRepo.getMessageCounts(keepId, lastSeenOpt)
      }
      (threadsById, lastMsgById, mutedById, threadActivityById, msgCountsById)
    }
    for {
      (threadsById, lastMsgById, mutedById, threadActivityById, msgCountsById) <- infoFut
      allUsers = threadsById.values.flatMap(_.allParticipants).toSet
      basicUserByIdMap <- shoebox.getBasicUsers(allUsers.toSeq)
    } yield lastMsgById.collect {
      case (keepId, Some(message)) =>
        val thread = threadsById(keepId)
        val threadActivity = threadActivityById(keepId)
        val (numMessages, numUnread) = msgCountsById(keepId)
        val muted = mutedById(keepId)

        def basicUserById(id: Id[User]) = basicUserByIdMap.getOrElse(id, throw new Exception(s"Could not get basic user data for $id in MessageThread ${thread.id.get}"))
        val basicNonUserParticipants = thread.participants.nonUserParticipants.keySet.map(nup => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
        val messageWithBasicUser = MessageWithBasicUser(
          id = message.pubId,
          createdAt = message.createdAt,
          text = message.messageText,
          source = message.source,
          auxData = None,
          url = message.sentOnUrl.getOrElse(thread.url),
          nUrl = thread.nUrl,
          message.from match {
            case MessageSender.User(id) => Some(BasicUserLikeEntity(basicUserById(id)))
            case MessageSender.NonUser(nup) => Some(BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
            case _ => None
          },
          thread.allParticipants.toSeq.map(u => BasicUserLikeEntity(basicUserById(u))) ++ basicNonUserParticipants.toSeq
        )
        val authorActivityInfos = threadActivity.filter(_.lastActive.isDefined)

        val lastSeenOpt: Option[DateTime] = threadActivity.find(_.userId == userId).flatMap(_.lastSeen)
        val unseenAuthors: Int = lastSeenOpt match {
          case Some(lastSeen) => authorActivityInfos.count(_.lastActive.get.isAfter(lastSeen))
          case None => authorActivityInfos.length
        }
        keepId -> MessageThreadNotification(
          message = message,
          thread = thread,
          messageWithBasicUser = messageWithBasicUser,
          unread = !message.from.asUser.contains(userId),
          originalAuthorIdx = 0,
          numUnseenAuthors = unseenAuthors,
          numAuthors = authorActivityInfos.length,
          numMessages = numMessages,
          numUnread = numUnread,
          muted = muted)
    }
  }

  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]]): Future[Map[Id[User], MessageThreadNotification]] = ???
}


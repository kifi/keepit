package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza.model._
import com.keepit.model.{ DeepLocator, Keep, NotificationCategory, User }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUser, BasicUserLikeEntity }
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }

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
  numUnreadMessages: Int,
  forceOverwrite: Boolean) // this flag will tell the extension to overwrite any existing notifications with this keep id
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
    numUnreadMessages = numUnread,
    forceOverwrite = false
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
    (__ \ 'unreadMessages).write[Int] and
    (__ \ 'overwrite).write[Boolean]
  )(unlift(MessageThreadNotification.unapply))
}

@ImplementedBy(classOf[MessageThreadNotificationBuilderImpl])
trait MessageThreadNotificationBuilder {
  import MessageThreadNotificationBuilder.PrecomputedInfo

  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]], precomputed: Option[PrecomputedInfo.BuildForKeeps] = None): Future[Map[Id[Keep], MessageThreadNotification]]
  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]], precomputed: Option[PrecomputedInfo.BuildForUsers] = None): Future[Map[Id[User], MessageThreadNotification]]

  // Convenience methods, just wrappers around the real methods above
  def buildForKeep(userId: Id[User], keepId: Id[Keep], precomputed: Option[PrecomputedInfo.BuildForKeep] = None): Future[Option[MessageThreadNotification]]
}

object MessageThreadNotificationBuilder {
  object PrecomputedInfo {
    case class BuildForKeeps(
      threadById: Option[Map[Id[Keep], MessageThread]] = None,
      lastMsgById: Option[Map[Id[Keep], Option[ElizaMessage]]] = None,
      mutedById: Option[Map[Id[Keep], Boolean]] = None,
      threadActivityById: Option[Map[Id[Keep], Seq[UserThreadActivity]]] = None,
      msgCountById: Option[Map[Id[Keep], MessageCount]] = None,
      basicUserMap: Option[Map[Id[User], BasicUser]] = None)
    case class BuildForKeep(
        thread: Option[MessageThread] = None,
        lastMsg: Option[Option[ElizaMessage]] = None,
        muted: Option[Boolean] = None,
        threadActivity: Option[Seq[UserThreadActivity]] = None,
        msgCount: Option[MessageCount] = None,
        basicUserMap: Option[Map[Id[User], BasicUser]] = None) {
      def pluralize(keepId: Id[Keep]) = BuildForKeeps(
        thread.map(x => Map(keepId -> x)),
        lastMsg.map(x => Map(keepId -> x)),
        muted.map(x => Map(keepId -> x)),
        threadActivity.map(x => Map(keepId -> x)),
        msgCount.map(x => Map(keepId -> x)),
        basicUserMap
      )
    }

    case class BuildForUsers(dummy: Int)
  }
}

@Singleton
class MessageThreadNotificationBuilderImpl @Inject() (
  db: Database,
  messageThreadRepo: MessageThreadRepo,
  userThreadRepo: UserThreadRepo,
  messageRepo: MessageRepo,
  messageFetchingCommander: MessageFetchingCommander,
  shoebox: ShoeboxServiceClient,
  implicit val publicIdConfig: PublicIdConfiguration,
  implicit val executionContext: ExecutionContext)
    extends MessageThreadNotificationBuilder {

  import MessageThreadNotificationBuilder.PrecomputedInfo

  def buildForKeep(userId: Id[User], keepId: Id[Keep], precomputed: Option[PrecomputedInfo.BuildForKeep] = None): Future[Option[MessageThreadNotification]] =
    buildForKeeps(userId, Set(keepId), precomputed.map(_.pluralize(keepId))).map(_.get(keepId))

  def buildForKeeps(userId: Id[User], keepIds: Set[Id[Keep]], precomputed: Option[PrecomputedInfo.BuildForKeeps] = None): Future[Map[Id[Keep], MessageThreadNotification]] = {
    val infoFut = db.readOnlyMasterAsync { implicit s =>
      val threadsById = precomputed.flatMap(_.threadById).getOrElse {
        messageThreadRepo.getByKeepIds(keepIds)
      }
      val lastMsgById = precomputed.flatMap(_.lastMsgById).getOrElse {
        keepIds.map { keepId => keepId -> messageRepo.getLatest(keepId) }.toMap
      }
      val mutedById = precomputed.flatMap(_.mutedById).getOrElse {
        keepIds.map { keepId => keepId -> userThreadRepo.isMuted(userId, keepId) }.toMap
      }
      val threadActivityById = precomputed.flatMap(_.threadActivityById).getOrElse {
        keepIds.map { keepId =>
          keepId -> userThreadRepo.getThreadActivity(keepId).sortBy { uta =>
            (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
          }
        }.toMap
      }
      val msgCountById = precomputed.flatMap(_.msgCountById).getOrElse {
        threadActivityById.map {
          case (keepId, activity) =>
            val lastSeenOpt = activity.find(_.userId == userId).flatMap(_.lastSeen)
            keepId -> messageRepo.getMessageCounts(keepId, lastSeenOpt)
        }
      }
      (threadsById, lastMsgById, mutedById, threadActivityById, msgCountById)
    }
    for {
      (threadsById, lastMsgById, mutedById, threadActivityById, msgCountById) <- infoFut
      basicUserByIdMap <- precomputed.flatMap(_.basicUserMap).map(Future.successful).getOrElse {
        val allUsers = threadsById.values.flatMap(_.allParticipants).toSet
        shoebox.getBasicUsers(allUsers.toSeq)
      }
    } yield lastMsgById.collect {
      case (keepId, Some(message)) =>
        val thread = threadsById(keepId)
        val threadActivity = threadActivityById(keepId)
        val MessageCount(numMessages, numUnread) = msgCountById(keepId)
        val muted = mutedById(keepId)

        val messageWithBasicUser = messageFetchingCommander.getMessageWithBasicUser(message, thread, basicUserByIdMap)
        val authorActivityInfos = threadActivity.filter(_.lastActive.isDefined)

        val lastSeenOpt: Option[DateTime] = threadActivity.find(_.userId == userId).flatMap(_.lastSeen)
        val unseenAuthors: Int = lastSeenOpt match {
          case Some(lastSeen) => authorActivityInfos.count(uta => uta.userId != userId && uta.lastActive.get.isAfter(lastSeen))
          case None => authorActivityInfos.count(_.userId != userId)
        }
        keepId -> MessageThreadNotification(
          message = message.withText(messageWithBasicUser.text),
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

  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]], precomputed: Option[PrecomputedInfo.BuildForUsers] = None): Future[Map[Id[User], MessageThreadNotification]] = ???
}


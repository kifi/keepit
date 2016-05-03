package com.keepit.eliza.commanders

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.core.{ iterableExtensionOps, optionExtensionOps, mapExtensionOps }
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.time._
import com.keepit.eliza.model._
import com.keepit.model._
import com.keepit.model.BasicKeepEvent.BasicKeepEventId
import com.keepit.model.{ CommonKeepEvent, DeepLocator, Keep, NotificationCategory, User }
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
  id: Option[BasicKeepEventId],
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
  numUnseenAuthors: Int, // if non-zero, counts as "unread = true"
  numMessages: Int,
  numUnreadMessages: Int, // used only for client bookkeeping
  forceOverwrite: Boolean) // this flag will tell the extension to overwrite any existing notifications with this keep id
object MessageThreadNotification {
  def apply(thread: MessageThread, threadStarter: ExternalId[User], messageWithBasicUser: MessageWithBasicUser,
    unread: Boolean, numUnseenAuthors: Int, numAuthors: Int,
    numMessages: Int, numUnread: Int, muted: Boolean)(implicit publicIdConfig: PublicIdConfiguration, airbrake: AirbrakeNotifier): MessageThreadNotification = {
    val orderedParticipants = messageWithBasicUser.participants.sortBy(x => x.fold(nu => (nu.firstName.getOrElse(""), nu.lastName.getOrElse("")), u => (u.firstName, u.lastName)))
    val indexOfFirstAuthor = orderedParticipants.zipWithIndex.collectFirst {
      case (Right(user), idx) if user.externalId == threadStarter => idx
    }.getOrElse {
      airbrake.notify(s"Thread starter is not one of the participants for keep ${thread.keepId}")
      0
    }
    MessageThreadNotification(
      id = Some(messageWithBasicUser.id),
      time = messageWithBasicUser.createdAt,
      author = Some(messageWithBasicUser.user),
      text = messageWithBasicUser.text,
      threadId = thread.pubKeepId,
      locator = thread.deepLocator,
      url = thread.url,
      title = thread.pageTitle,
      participants = orderedParticipants,
      unread = unread,
      muted = muted,
      category = NotificationCategory.User.MESSAGE,
      firstAuthor = indexOfFirstAuthor,
      numAuthors = numAuthors,
      numUnseenAuthors = numUnseenAuthors,
      numMessages = numMessages,
      numUnreadMessages = numUnread,
      forceOverwrite = false)
  }
  implicit def writes: Writes[MessageThreadNotification] = (
    (__ \ 'id).writeNullable[BasicKeepEventId] and
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
  def buildForUsersFromEvent(userIds: Set[Id[User]], keepId: Id[Keep], event: CommonKeepEvent, precomputedInfo: Option[PrecomputedInfo.BuildForEvent] = None): Future[Map[Id[User], MessageThreadNotification]]

  // Convenience methods, just wrappers around the real methods above
  def buildForKeep(userId: Id[User], keepId: Id[Keep], precomputed: Option[PrecomputedInfo.BuildForKeep] = None): Future[Option[MessageThreadNotification]]
}

object MessageThreadNotificationBuilder {
  object PrecomputedInfo {
    case class BuildForKeeps(
      threadById: Option[Map[Id[Keep], MessageThread]] = None,
      lastMsgById: Option[Map[Id[Keep], ElizaMessage]] = None,
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
        lastMsg.map(x => x.map(keepId -> _).toMap),
        muted.map(x => Map(keepId -> x)),
        threadActivity.map(x => Map(keepId -> x)),
        msgCount.map(x => Map(keepId -> x)),
        basicUserMap
      )
    }
    case class BuildForEvent(
      thread: Option[MessageThread] = None,
      basicUserById: Option[Map[Id[User], BasicUser]] = None,
      messageWithBasicUser: Option[MessageWithBasicUser] = None)

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
  implicit val airbrake: AirbrakeNotifier,
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
        keepIds.flatAugmentWith(messageRepo.getLatest).toMap
      }.filterValues(_.auxData.forall(SystemMessageData.isFullySupported))

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
    } yield threadsById.map {
      case (keepId, thread) =>
        val thread = threadsById(keepId)
        val messageOpt = lastMsgById.get(keepId)
        val threadStarter = basicUserByIdMap(thread.startedBy).externalId
        val threadActivity = threadActivityById(keepId)
        val MessageCount(numMessages, numUnread) = msgCountById(keepId)
        val muted = mutedById(keepId)

        val author = messageOpt.map(_.from).collect {
          case MessageSender.User(id) => BasicUserLikeEntity(basicUserByIdMap(id))
          case MessageSender.NonUser(nup) => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup))
        }.getOrElse {
          BasicUserLikeEntity(BasicUser(ExternalId[User]("42424242-4242-4242-4242-000000000001"), "Kifi", "", "0.jpg", Username("sssss")))
        }
        val authorActivityInfos = threadActivity.filter(_.lastActive.isDefined)

        val lastSeenOpt: Option[DateTime] = threadActivity.find(_.userId == userId).flatMap(_.lastSeen)
        val numUnseenAuthors: Int = lastSeenOpt match {
          case Some(lastSeen) => authorActivityInfos.count(uta => uta.userId != userId && uta.lastActive.get.isAfter(lastSeen))
          case None => authorActivityInfos.count(_.userId != userId)
        }
        keepId -> {
          val allParticipants = thread.allParticipants.toSeq.map(u => BasicUserLikeEntity(basicUserByIdMap(u))) ++
            thread.participants.nonUserParticipants.keySet.map(nup => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nup)))
          val orderedParticipants = allParticipants.sortBy(x => x.fold(nu => (nu.firstName.getOrElse(""), nu.lastName.getOrElse("")), u => (u.firstName, u.lastName)))
          val indexOfFirstAuthor = orderedParticipants.zipWithIndex.collectFirst {
            case (Right(user), idx) if user.externalId == threadStarter => idx
          }.getOrElse {
            airbrake.notify(s"Thread starter is not one of the participants for keep ${thread.keepId}")
            0
          }
          MessageThreadNotification(
            id = messageOpt.map(msg => BasicKeepEventId.fromPubMsg(msg.pubId)),
            time = messageOpt.map(_.createdAt).getOrElse(thread.createdAt),
            author = Some(author),
            text = messageOpt.map { message =>
              message.auxData.map(SystemMessageData.generateMessageText(_, basicUserByIdMap)).getOrElse(message.messageText)
            }.getOrElse(thread.url),
            threadId = thread.pubKeepId,
            locator = thread.deepLocator,
            url = messageOpt.flatMap(_.sentOnUrl).getOrElse(thread.url),
            title = thread.pageTitle,
            participants = orderedParticipants,
            unread = !messageOpt.exists(_.from.asUser.safely.contains(userId)),
            muted = muted,
            category = NotificationCategory.User.MESSAGE,
            firstAuthor = indexOfFirstAuthor,
            numAuthors = authorActivityInfos.length,
            numUnseenAuthors = numUnseenAuthors,
            numMessages = numMessages,
            numUnreadMessages = numUnread,
            forceOverwrite = false
          )
        }
    }
  }

  def buildForUsersFromEvent(userIds: Set[Id[User]], keepId: Id[Keep], event: CommonKeepEvent, precomputedInfo: Option[PrecomputedInfo.BuildForEvent] = None): Future[Map[Id[User], MessageThreadNotification]] = {
    val (thread, threadActivity, messageCountByUser, mutedByUser) = db.readOnlyMaster { implicit s =>
      val thread = precomputedInfo.flatMap(_.thread).getOrElse(messageThreadRepo.getByKeepId(keepId).get)
      val threadActivity = userThreadRepo.getThreadActivity(keepId).sortBy { uta => (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id) }
      val messageCountByUser = userIds.map { userId =>
        val lastSeenOpt = threadActivity.find(_.userId == userId).flatMap(_.lastSeen)
        userId -> messageRepo.getMessageCounts(keepId, lastSeenOpt) // todo(cam): optimize this
      }.toMap
      val mutedByUser = userThreadRepo.isMutedByUser(userIds, keepId)

      (thread, threadActivity, messageCountByUser, mutedByUser)
    }

    for {
      basicUserById <- precomputedInfo.flatMap(_.basicUserById).map(Future.successful)
        .getOrElse(shoebox.getBasicUsers(thread.allParticipants.toSeq))
    } yield {
      val message = precomputedInfo.flatMap(_.messageWithBasicUser).getOrElse(messageFetchingCommander.getMessageWithBasicUser(Left(event), thread, basicUserById))

      userIds.map { userId =>
        val authorActivityInfos = threadActivity.filter(_.lastActive.isDefined)
        val lastSeenOpt = threadActivity.find(_.userId == userId).flatMap(_.lastSeen)
        val unseenAuthors = lastSeenOpt match {
          case Some(lastSeen) => authorActivityInfos.count(uta => uta.userId != userId && uta.lastActive.get.isAfter(lastSeen))
          case None => authorActivityInfos.count(_.userId != userId)
        }
        val MessageCount(numMessages, numUnread) = messageCountByUser(userId)

        userId -> MessageThreadNotification(
          thread = thread,
          threadStarter = basicUserById(thread.startedBy).externalId,
          messageWithBasicUser = message,
          unread = !message.user.right.toOption.exists(_.externalId == basicUserById(userId).externalId),
          numUnseenAuthors = unseenAuthors,
          numAuthors = authorActivityInfos.length,
          numMessages = numMessages,
          numUnread = numUnread,
          muted = mutedByUser(userId)
        )
      }.toMap

    }
  }

  def buildForUsers(keepId: Id[Keep], userIds: Set[Id[User]], precomputed: Option[PrecomputedInfo.BuildForUsers] = None): Future[Map[Id[User], MessageThreadNotification]] = ???
}


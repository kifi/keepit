package com.keepit.eliza.commanders

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.{ ByteBuffer, CharBuffer }

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.discussion.Message
import com.keepit.eliza._
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ UserThread, UserThreadActivity, UserThreadNotification, _ }
import com.keepit.eliza.util.MessageFormatter
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.realtime._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.BasicUserLikeEntity
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._

import scala.concurrent.{ ExecutionContext, Future }

case class UserThreadQuery(
  keepIds: Option[Set[Id[Keep]]] = None,
  beforeTime: Option[DateTime] = None,
  onlyStartedBy: Option[Id[User]] = None,
  onlyUnread: Option[Boolean] = None,
  onUri: Option[Id[NormalizedURI]] = None,
  limit: Int)

case class UnreadThreadCounts(total: Int, unmuted: Int)
case class MessageNotification(
  // Info about the message
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
object MessageNotification {
  // TODO(ryan): pray for forgiveness for this travesty
  def apply(message: ElizaMessage, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser,
    unread: Boolean, originalAuthorIdx: Int, numUnseenAuthors: Int, numAuthors: Int,
    numMessages: Int, numUnread: Int, muted: Boolean)(implicit publicIdConfig: PublicIdConfiguration): MessageNotification = MessageNotification(
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
  implicit def writes: Writes[MessageNotification] = (
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
  )(unlift(MessageNotification.unapply))
}

class NotificationDeliveryCommander @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    nonUserThreadRepo: NonUserThreadRepo,
    messageRepo: MessageRepo,
    db: Database,
    notificationRouter: WebSocketRouter,
    shoebox: ShoeboxServiceClient,
    messagingAnalytics: MessagingAnalytics,
    pushNotifier: MobilePushNotifier,
    notificationJsonMaker: NotificationJsonMaker,
    basicMessageCommander: MessageFetchingCommander,
    emailCommander: ElizaEmailCommander,
    notificationRepo: NotificationRepo,
    implicit val publicIdConfig: PublicIdConfiguration,
    implicit val executionContext: ExecutionContext) extends Logging {

  implicit val messageNotificationWrites: Writes[MessageNotification] = MessageNotification.writes

  def notifySendMessage(from: Id[User], message: ElizaMessage, thread: MessageThread, orderedMessageWithBasicUser: MessageWithBasicUser, originalAuthor: Int, numAuthors: Int, numMessages: Int, numUnread: Int): Unit = {
    val notifJson = MessageNotification(
      message = message,
      thread = thread,
      messageWithBasicUser = orderedMessageWithBasicUser,
      unread = false,
      originalAuthorIdx = originalAuthor,
      numUnseenAuthors = 0,
      numAuthors = numAuthors,
      numMessages = numMessages,
      numUnread = numUnread,
      muted = false)
    sendToUser(from, Json.arr("notification", notifJson))
  }

  def updateEmailParticipantThreads(thread: MessageThread, newMessage: ElizaMessage): Unit = {
    val emailParticipants = thread.participants.allNonUsers.collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }
    val emailSenderOption = newMessage.from.asNonUser.collect {
      case emailSender: NonUserEmailParticipant => emailSender.address
    }
    val emailRecipients = emailSenderOption.map(emailParticipants - _).getOrElse(emailParticipants)

    if (emailRecipients.nonEmpty) {
      db.readWrite { implicit session =>
        val nonUserThreads = nonUserThreadRepo.getByKeepId(thread.keepId)
        val recipientThreads = emailSenderOption match {
          case Some(emailSender) => nonUserThreads.filter(_.participant.identifier != emailSender.address)
          case None => nonUserThreads
        }
        recipientThreads.foreach { recipientThread =>
          nonUserThreadRepo.save(recipientThread.copy(threadUpdatedByOtherAt = Some(newMessage.createdAt)))
        }
      }
    }
  }

  def notifyEmailParticipants(thread: MessageThread): Unit = { emailCommander.notifyEmailUsers(thread) }

  def notifyAddParticipants(newParticipants: Seq[Id[User]], newNonUserParticipants: Seq[NonUserParticipant], thread: MessageThread, message: ElizaMessage, adderUserId: Id[User]): Unit = {
    new SafeFuture(shoebox.getBasicUsers(thread.participants.allUsers.toSeq) map { basicUsers =>
      val adderUserName = basicUsers.get(adderUserId).map { bu => bu.firstName + " " + bu.lastName }.get
      val theTitle: String = thread.pageTitle.getOrElse("New conversation")
      val participants: Seq[BasicUserLikeEntity] =
        basicUsers.values.toSeq.map(u => BasicUserLikeEntity(u)) ++
          thread.participants.allNonUsers.toSeq.map(nu => BasicUserLikeEntity(NonUserParticipant.toBasicNonUser(nu)))
      val notificationJson = Json.obj(
        "id" -> message.pubId,
        "time" -> message.createdAt,
        "thread" -> thread.pubKeepId,
        "text" -> s"$adderUserName added you to a conversation.",
        "url" -> message.sentOnUrl.getOrElse[String](thread.url),
        "title" -> theTitle,
        "author" -> basicUsers(adderUserId),
        "participants" -> participants,
        "locator" -> thread.deepLocator,
        "unread" -> true,
        "category" -> NotificationCategory.User.MESSAGE.category
      )
      db.readWrite { implicit session =>
        newParticipants.map { pUserId =>
          userThreadRepo.intern(UserThread.forMessageThread(thread)(user = pUserId))
        }

        newNonUserParticipants.foreach { nup =>
          nonUserThreadRepo.save(NonUserThread(
            createdBy = adderUserId,
            participant = nup,
            keepId = thread.keepId,
            uriId = Some(thread.uriId),
            notifiedCount = 0,
            lastNotifiedAt = None,
            threadUpdatedByOtherAt = Some(message.createdAt),
            muted = false
          ))
        }
      }

      Future.sequence(newParticipants.map { userId =>
        recreateNotificationForAddedParticipant(userId, thread)
      }) map { permanentNotifications =>
        newParticipants.zip(permanentNotifications) foreach {
          case (userId, permanentNotification) =>
            sendToUser(userId, Json.arr("notification", notificationJson, permanentNotification))
        }
        val messageWithBasicUser = basicMessageCommander.getMessageWithBasicUser(message.pubId, message.createdAt, "", message.source, message.auxData, "", "", None, participants)
        messageWithBasicUser.map { augmentedMessage =>
          thread.participants.allUsers.par.foreach { userId =>
            sendToUser(userId, Json.arr("message", thread.pubKeepId, augmentedMessage))
            sendToUser(userId, Json.arr("thread_participants", thread.pubKeepId, participants))
          }
        }
        emailCommander.notifyAddedEmailUsers(thread, newNonUserParticipants)
      }
    })
  }

  def notifyMessage(userId: Id[User], keepId: PublicId[Keep], message: MessageWithBasicUser): Unit =
    sendToUser(userId, Json.arr("message", keepId, message))

  def notifyRead(userId: Id[User], keepId: Id[Keep], messageId: Id[ElizaMessage], nUrl: String, creationDate: DateTime): Unit = {
    // TODO(ryan): stop manually forcing the date to go to millis, fix the Json formatter
    sendToUser(userId, Json.arr("message_read", nUrl, Keep.publicId(keepId), creationDate.getMillis, Message.publicId(ElizaMessage.toCommonId(messageId))))
    notifyUnreadCount(userId, keepId)
  }

  def notifyUnread(userId: Id[User], keepId: Id[Keep], messageId: Id[ElizaMessage], nUrl: String, creationDate: DateTime): Unit = {
    sendToUser(userId, Json.arr("message_unread", nUrl, Keep.publicId(keepId), creationDate, Message.publicId(ElizaMessage.toCommonId(messageId))))
    notifyUnreadCount(userId, keepId)
  }

  private def notifyUnreadCount(userId: Id[User], keepId: Id[Keep]): Unit = {
    val (_, unreadUnmutedThreadCount, unreadNotificationCount) = getUnreadCounts(userId)
    val totalUnreadCount = unreadUnmutedThreadCount + unreadNotificationCount
    sendToUser(userId, Json.arr("unread_notifications_count", totalUnreadCount, unreadUnmutedThreadCount, unreadNotificationCount))
    val notification = MessageThreadPushNotification(Keep.publicId(keepId), totalUnreadCount, None, None)
    sendPushNotification(userId, notification)
  }

  def notifyUnreadCount(userId: Id[User]): Unit = {
    val (_, unreadUnmutedThreadCount, unreadNotificationCount) = getUnreadCounts(userId)
    val totalUnreadCount = unreadUnmutedThreadCount + unreadNotificationCount
    sendToUser(userId, Json.arr("unread_notifications_count", totalUnreadCount, unreadUnmutedThreadCount, unreadNotificationCount))
    sendPushNotification(userId, MessageCountPushNotification(totalUnreadCount))
  }

  def notifyRemoveThread(userId: Id[User], keepId: Id[Keep]): Unit =
    sendToUser(userId, Json.arr("remove_notification", Keep.publicId(keepId)))

  def sendToUser(userId: Id[User], data: JsArray): Unit =
    notificationRouter.sendToUser(userId, data)

  def sendUserPushNotification(userId: Id[User], message: String, recipientUserId: ExternalId[User], username: Username, pictureUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int] = {
    val notification = UserPushNotification(message = Some(message), userExtId = recipientUserId, username = username, pictureUrl = pictureUrl, unvisitedCount = getTotalUnreadUnmutedCount(userId), category = category, experiment = pushNotificationExperiment)
    sendPushNotification(userId, notification)
  }

  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean): Future[Int] = {
    val notification = LibraryUpdatePushNotification(message = Some(message), libraryId = libraryId, libraryUrl = libraryUrl, unvisitedCount = getTotalUnreadUnmutedCount(userId), category = category, experiment = pushNotificationExperiment)
    sendPushNotification(userId, notification, force)
  }

  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean): Future[Int] = {
    val notification = SimplePushNotification(message = Some(message), unvisitedCount = getTotalUnreadUnmutedCount(userId), category = category, experiment = pushNotificationExperiment)
    sendPushNotification(userId, notification, force)
  }

  def sendOrgPushNotification(request: OrgPushNotificationRequest): Future[Int] = {
    val notification = OrganizationPushNotification(message = Some(request.message), unvisitedCount = getTotalUnreadUnmutedCount(request.userId), category = request.category, experiment = request.pushNotificationExperiment)
    sendPushNotification(request.userId, notification, request.force)
  }

  def buildNotificationForMessageThread(userId: Id[User], keep: Id[Keep]): Future[Option[MessageNotification]] = {
    buildNotificationForMessageThreads(userId, Set(keep)).map(_.get(keep))

  }
  def buildNotificationForMessageThreads(userId: Id[User], keeps: Set[Id[Keep]]): Future[Map[Id[Keep], MessageNotification]] = {
    val infoFut = db.readOnlyMasterAsync { implicit s =>
      val threadsById = threadRepo.getByKeepIds(keeps)
      val lastMsgById = keeps.map { keepId => keepId -> messageRepo.getLatest(keepId) }.toMap
      val mutedById = keeps.map { keepId => keepId -> userThreadRepo.isMuted(userId, keepId) }.toMap
      val threadActivityById = keeps.map { keepId =>
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
        keepId -> MessageNotification(
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
  def getNotificationsByUser(userId: Id[User], utq: UserThreadQuery, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    val (uts, mts) = db.readOnlyReplica { implicit session =>
      val uts = userThreadRepo.getThreadsForUser(userId, utq)
      val mtMap = threadRepo.getByKeepIds(uts.map(_.keepId).toSet)
      (uts, mtMap)
    }
    val notifJsonsByThreadFut = buildNotificationForMessageThreads(userId, mts.keySet)
    notifJsonsByThreadFut.flatMap { notifJsonsByThread =>
      val inputs = uts.flatMap { ut => notifJsonsByThread.get(ut.keepId).map(notif => (Json.toJson(notif), ut.unread, ut.uriId)) }
      notificationJsonMaker.make(inputs, includeUriSummary)
    }
  }

  def sendNotificationForMessage(userId: Id[User], message: ElizaMessage, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser, orderedActivityInfo: Seq[UserThreadActivity]): Unit = {
    SafeFuture {
      val authorActivityInfos = orderedActivityInfo.filter(_.lastActive.isDefined)
      val lastSeenOpt: Option[DateTime] = orderedActivityInfo.find(_.userId == userId).flatMap(_.lastSeen)
      val unseenAuthors: Int = lastSeenOpt match {
        case Some(lastSeen) => authorActivityInfos.count(_.lastActive.get.isAfter(lastSeen))
        case None => authorActivityInfos.length
      }
      val (numMessages: Int, numUnread: Int, muted: Boolean) = db.readOnlyMaster { implicit session =>
        val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.keepId, lastSeenOpt)
        val muted = userThreadRepo.isMuted(userId, thread.keepId)
        (numMessages, numUnread, muted)
      }

      val notif = MessageNotification(
        message = message,
        thread = thread,
        messageWithBasicUser = messageWithBasicUser,
        unread = true,
        originalAuthorIdx = 0,
        numUnseenAuthors = unseenAuthors,
        numAuthors = authorActivityInfos.length,
        numMessages = numMessages,
        numUnread = numUnread,
        muted = muted)

      messagingAnalytics.sentNotificationForMessage(userId, message, thread, muted)
      shoebox.createDeepLink(message.from.asUser, userId, thread.uriId, thread.deepLocator)

      val (unreadMessages, unreadNotifications) = db.readOnlyMaster { implicit session =>
        (userThreadRepo.getUnreadThreadCounts(userId).unmuted, notificationRepo.getUnreadNotificationsCount(Recipient(userId)))
      }

      notificationRouter.sendToUser(userId, Json.arr("notification", notif))
      notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))

      if (!muted) {
        val sender = messageWithBasicUser.user match {
          case Some(BasicUserLikeEntity.user(bu)) => bu.firstName + ": "
          case Some(BasicUserLikeEntity.nonUser(bnu)) => bnu.firstName.getOrElse(bnu.id) + ": "
          case _ => ""
        }
        val notifText = sender + MessageFormatter.toText(message.messageText)
        val sound = if (numMessages > 1) MobilePushNotifier.MoreMessageNotificationSound else MobilePushNotifier.DefaultNotificationSound
        val notification = MessageThreadPushNotification(thread.pubKeepId, unreadMessages + unreadNotifications, Some(trimAtBytes(notifText, 128, UTF_8)), Some(sound))
        sendPushNotification(userId, notification)
      }
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(userId), UserThreadNotification(message.keepId, message.id.get))
  }

  private def trimAtBytes(str: String, len: Int, charset: Charset) = { //Conner's Algorithm
    val outBuf = ByteBuffer.wrap(new Array[Byte](len))
    val inBuf = CharBuffer.wrap(str.toCharArray)
    charset.newEncoder().encode(inBuf, outBuf, true)
    new String(outBuf.array, 0, outBuf.position(), charset)
  }

  //for a given user and thread make sure the notification is correct
  private def recreateNotificationForAddedParticipant(userId: Id[User], thread: MessageThread): Future[JsValue] = {
    db.readOnlyMaster { implicit session => messageRepo.getLatest(thread.keepId) }.foreach { message =>
      messagingAnalytics.sentNotificationForMessage(userId, message, thread, muted = false)
      shoebox.createDeepLink(message.from.asUser, userId, thread.uriId, thread.deepLocator)
    }
    getNotificationsByUser(userId, UserThreadQuery(keepIds = Some(Set(thread.keepId)), limit = 1), includeUriSummary = false).map(_.head.obj)
  }

  def setAllNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting all Notifications as read for user $userId.")
    db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markAllRead(userId)
      notificationRepo.setAllRead(Recipient(userId))
    }
  }

  def setSystemNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting System Notifications as read for user $userId")
    db.readWrite(attempts = 2) { implicit session =>
      notificationRepo.setAllRead(Recipient(userId))
    }
  }

  def setMessageNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting Messaging Notifications as read for user $userId")
    db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.markAllRead(userId)
    }
  }

  // todo(Léo): Why send unread counts computed before marking stuff as read?
  def setAllNotificationsReadBefore(user: Id[User], messageId: Id[ElizaMessage], unreadMessages: Int, unreadNotifications: Int): DateTime = {
    val message = db.readWrite(attempts = 2) { implicit session =>
      val message = messageRepo.get(messageId)
      userThreadRepo.markAllReadAtOrBefore(user, message.createdAt)
      notificationRepo.setAllReadBefore(Recipient(user), message.createdAt)
      message
    }
    notificationRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
    val notification = MessageThreadPushNotification(message.pubKeepId, unreadMessages + unreadNotifications, None, None)
    sendPushNotification(user, notification)
    message.createdAt
  }

  def getSendableNotification(userId: Id[User], keepId: Id[Keep], includeUriSummary: Boolean): Future[NotificationJson] = {
    getNotificationsByUser(userId, UserThreadQuery(keepIds = Some(Set(keepId)), limit = 1), includeUriSummary).map(_.head)
  }

  def getUnreadThreadNotifications(userId: Id[User]): Seq[UserThreadNotification] = {
    db.readOnlyReplica { implicit s => userThreadRepo.getUnreadThreadNotifications(userId) }
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(limit = howMany), includeUriSummary)
  }

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(beforeTime = Some(time), limit = howMany), includeUriSummary)
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[(Seq[NotificationJson], Int)] = {
    val noticesFuture = getNotificationsByUser(userId, UserThreadQuery(onlyUnread = Some(true), limit = howMany), includeUriSummary)
    new SafeFuture(noticesFuture map { notices =>
      val numTotal = if (notices.length < howMany) {
        notices.length
      } else {
        db.readOnlyReplica { implicit session =>
          userThreadRepo.getUnreadThreadCounts(userId).total
        }
      }
      (notices, numTotal)
    })
  }

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(beforeTime = Some(time), onlyUnread = Some(true), limit = howMany), includeUriSummary)
  }

  def getLatestSentSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(onlyStartedBy = Some(userId), limit = howMany), includeUriSummary)
  }

  def getSentSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(onlyStartedBy = Some(userId), beforeTime = Some(time), limit = howMany), includeUriSummary)
  }

  def getLatestSendableNotificationsForPage(userId: Id[User], url: String, howMany: Int, includeUriSummary: Boolean): Future[(String, Seq[NotificationJson], Int, Int)] = {
    shoebox.getNormalizedUriByUrlOrPrenormalize(url).flatMap {
      case Right(prenormalizedUrl) =>
        Future.successful(prenormalizedUrl, Seq.empty, 0, 0)
      case Left(nUri) =>
        val noticesFuture = getNotificationsByUser(userId, UserThreadQuery(onUri = Some(nUri.id.get), limit = howMany), includeUriSummary)
        noticesFuture.map { notices =>
          val unreadCounts = db.readOnlyReplica { implicit session =>
            userThreadRepo.getThreadCountsForUri(userId, nUri.id.get)
          }
          (nUri.url, notices, unreadCounts.total, unreadCounts.unmuted)
        }
    }
  }

  def getSendableNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    new SafeFuture(shoebox.getNormalizedURIByURL(url) flatMap {
      case Some(nUri) =>
        getNotificationsByUser(userId, UserThreadQuery(onUri = Some(nUri.id.get), beforeTime = Some(time), limit = howMany), includeUriSummary)
      case _ => Future.successful(Seq.empty)
    })
  }

  def connectedSockets: Int = notificationRouter.connectedSockets

  def notifyUserAboutMuteChange(userId: Id[User], keepId: PublicId[Keep], mute: Boolean) = {
    notificationRouter.sendToUser(userId, Json.arr("thread_muted", keepId, mute))
  }

  def getUnreadUnmutedThreads(userId: Id[User], howMany: Int): Seq[UserThreadView] = {
    db.readOnlyReplica { implicit s =>
      val userThreads = userThreadRepo.getLatestUnreadUnmutedThreads(userId, howMany)
      userThreads.map { userThread =>
        val messageThread = threadRepo.getByKeepId(userThread.keepId).get

        val messagesSinceLastSeen = userThread.lastSeen map { seenAt =>
          messageRepo.getAfter(userThread.keepId, seenAt)
        } getOrElse {
          messageRepo.get(userThread.keepId, 0)
        }

        UserThread.toUserThreadView(userThread, messagesSinceLastSeen, messageThread)
      }
    }
  }

  def getUnreadCounts(userId: Id[User]): (Int, Int, Int) = db.readOnlyMaster { implicit session =>
    val unreadThreadCount = userThreadRepo.getUnreadThreadCounts(userId)
    val unreadNotificationCount = notificationRepo.getUnreadNotificationsCount(Recipient(userId))
    (unreadThreadCount.total, unreadThreadCount.unmuted, unreadNotificationCount)
  }

  def getTotalUnreadUnmutedCount(userId: Id[User]): Int = {
    val (_, unreadUnmutedThreadCount, unreadNotificationCount) = getUnreadCounts(userId)
    unreadUnmutedThreadCount + unreadNotificationCount
  }

  private def sendPushNotification(userId: Id[User], notification: PushNotification, force: Boolean = false): Future[Int] = {
    pushNotifier.notifyUser(userId, notification, force)
  }
}

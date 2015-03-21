package com.keepit.eliza.commanders

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.{ ByteBuffer, CharBuffer }

import com.google.inject.Inject
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ Notification, UserThread, UserThreadActivity, _ }
import com.keepit.eliza.util.MessageFormatter
import com.keepit.model.{ NotificationCategory, User }
import com.keepit.realtime.{ MessageThreadPushNotification, PushNotification, UrbanAirship }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicNonUser, BasicUser, BasicUserLikeEntity }
import org.joda.time.DateTime
import play.api.libs.json.{ JsArray, _ }

import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.util.{ Failure, Success, Try }

class NotificationCommander @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    nonUserThreadRepo: NonUserThreadRepo,
    messageRepo: MessageRepo,
    db: Database,
    notificationRouter: WebSocketRouter,
    shoebox: ShoeboxServiceClient,
    messagingAnalytics: MessagingAnalytics,
    urbanAirship: UrbanAirship,
    notificationJsonMaker: NotificationJsonMaker,
    basicMessageCommander: MessageFetchingCommander,
    emailCommander: ElizaEmailCommander,
    implicit val executionContext: ExecutionContext) extends Logging {

  def notifySendMessage(from: Id[User], message: Message, thread: MessageThread, orderedMessageWithBasicUser: MessageWithBasicUser, originalAuthor: Int, numAuthors: Int, numMessages: Int, numUnread: Int): Unit = {
    val notifJson = buildMessageNotificationJson(
      message = message,
      thread = thread,
      messageWithBasicUser = orderedMessageWithBasicUser,
      locator = "/messages/" + thread.externalId,
      unread = false,
      originalAuthorIdx = originalAuthor,
      unseenAuthors = 0,
      numAuthors = numAuthors,
      numMessages = numMessages,
      numUnread = numUnread,
      muted = false)
    db.readWrite(attempts = 2) { implicit session =>
      userThreadRepo.setNotification(from, thread.id.get, message, notifJson, false)
    }
    sendToUser(from, Json.arr("notification", notifJson))
  }

  def updateEmailParticipantThreads(thread: MessageThread, newMessage: Message): Unit = {
    val emailParticipants = thread.participants.map(_.allNonUsers).getOrElse(Set.empty).collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }
    val emailSenderOption = newMessage.from.asNonUser.collect {
      case emailSender: NonUserEmailParticipant => emailSender.address
    }
    val emailRecipients = emailSenderOption.map(emailParticipants - _).getOrElse(emailParticipants)

    if (emailRecipients.nonEmpty) {
      db.readWrite { implicit session =>
        val nonUserThreads = nonUserThreadRepo.getByMessageThreadId(thread.id.get)
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

  def notifyAddParticipants(newParticipants: Seq[Id[User]], newNonUserParticipants: Seq[NonUserParticipant], thread: MessageThread, message: Message, adderUserId: Id[User]): Unit = {
    new SafeFuture(shoebox.getBasicUsers(thread.participants.get.allUsers.toSeq) map { basicUsers =>
      val adderUserName = basicUsers(adderUserId).firstName + " " + basicUsers(adderUserId).lastName
      val theTitle: String = thread.pageTitle.getOrElse("New conversation")
      val participants: Seq[BasicUserLikeEntity] = basicUsers.values.toSeq ++ thread.participants.get.allNonUsers.map(NonUserParticipant.toBasicNonUser).toSeq
      val notificationJson = Json.obj(
        "id" -> message.externalId.id,
        "time" -> message.createdAt,
        "thread" -> thread.externalId.id,
        "text" -> s"$adderUserName added you to a conversation.",
        "url" -> thread.nUrl,
        "title" -> theTitle,
        "author" -> basicUsers(adderUserId),
        "participants" -> participants,
        "locator" -> ("/messages/" + thread.externalId),
        "unread" -> true,
        "category" -> NotificationCategory.User.MESSAGE.category
      )
      db.readWrite { implicit session =>
        newParticipants.map { pUserId =>
          userThreadRepo.save(UserThread(
            id = None,
            user = pUserId,
            threadId = thread.id.get,
            uriId = thread.uriId,
            lastSeen = None,
            unread = true,
            lastMsgFromOther = None,
            lastNotification = JsNull
          ))
        }

        newNonUserParticipants.foreach { nup =>
          nonUserThreadRepo.save(NonUserThread(
            createdBy = adderUserId,
            participant = nup,
            threadId = thread.id.get,
            uriId = thread.uriId,
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
        newParticipants.zip(permanentNotifications) map {
          case (userId, permanentNotification) =>
            sendToUser(userId, Json.arr("notification", notificationJson, permanentNotification))
        }
        val messageWithBasicUser = basicMessageCommander.getMessageWithBasicUser(message.externalId, message.createdAt, "", message.source, message.auxData, "", "", None, participants)
        messageWithBasicUser.map { augmentedMessage =>
          thread.participants.map(_.allUsers.par.foreach { userId =>
            sendToUser(userId, Json.arr("message", thread.externalId.id, augmentedMessage))
            sendToUser(userId, Json.arr("thread_participants", thread.externalId.id, participants))
          })
        }
        emailCommander.notifyAddedEmailUsers(thread, newNonUserParticipants)
      }
    })
  }

  def notifyMessage(userId: Id[User], threadExtId: ExternalId[MessageThread], message: MessageWithBasicUser): Unit =
    sendToUser(userId, Json.arr("message", threadExtId.id, message))

  def notifyRead(userId: Id[User], threadExtId: ExternalId[MessageThread], msgExtId: ExternalId[Message], nUrl: String, creationDate: DateTime, unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("message_read", nUrl, threadExtId.id, creationDate, msgExtId.id))
    notifyUnreadCount(userId, threadExtId, unreadCount)
  }

  def notifyUnread(userId: Id[User], threadExtId: ExternalId[MessageThread], msgExtId: ExternalId[Message], nUrl: String, creationDate: DateTime, unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("message_unread", nUrl, threadExtId.id, creationDate, msgExtId.id))
    notifyUnreadCount(userId, threadExtId, unreadCount)
  }

  private def notifyUnreadCount(userId: Id[User], threadExtId: ExternalId[MessageThread], unreadCount: Int): Unit = {
    sendToUser(userId, Json.arr("unread_notifications_count", unreadCount))
    val notification = MessageThreadPushNotification(threadExtId, unreadCount, None, None)
    sendPushNotification(userId, notification)
  }

  def notifyRemoveThread(userId: Id[User], threadExtId: ExternalId[MessageThread]): Unit =
    sendToUser(userId, Json.arr("remove_thread", threadExtId.id))

  def sendToUser(userId: Id[User], data: JsArray): Unit =
    notificationRouter.sendToUser(userId, data)

  def createGlobalNotification(userIds: Set[Id[User]], title: String, body: String, linkText: String, linkUrl: String, imageUrl: String, sticky: Boolean, category: NotificationCategory, unread: Boolean = true, extra: Option[JsObject]) = {
    val (message, thread) = db.readWrite { implicit session =>
      val mtps = MessageThreadParticipants(userIds)
      val thread = threadRepo.save(MessageThread(
        uriId = None,
        url = None,
        nUrl = None,
        pageTitle = None,
        participants = Some(mtps),
        participantsHash = Some(mtps.hash),
        replyable = false
      ))

      val message = messageRepo.save(Message(
        from = MessageSender.System,
        thread = thread.id.get,
        threadExtId = thread.externalId,
        messageText = s"$title (on $linkText): $body",
        source = Some(MessageSource.SERVER),
        sentOnUrl = Some(linkUrl),
        sentOnUriId = None
      ))

      (message, thread)
    }
    SafeFuture {
      val notificationAttempts = userIds.map { userId =>
        //Stop gap to avoid overwhelming shoebox via heimal
        Thread.sleep(100) //TODO: Remove this an replace with proper throtteling + queue for heimdal events
        Try {
          val categoryString = NotificationCategory.User.kifiMessageFormattingCategory.get(category) getOrElse "global"
          val notifJson = Json.obj(
            "id" -> message.externalId.id,
            "time" -> message.createdAt,
            "thread" -> message.threadExtId.id,
            "unread" -> true,
            "category" -> categoryString,
            "fullCategory" -> category.category,
            "title" -> title,
            "bodyHtml" -> body,
            "linkText" -> linkText,
            "url" -> linkUrl,
            "isSticky" -> sticky,
            "image" -> imageUrl,
            "extra" -> extra
          )
          notificationRouter.sendToUser(userId, Json.arr("notification", notifJson))

          db.readWrite { implicit session =>
            userThreadRepo.save(UserThread(
              id = None,
              user = userId,
              threadId = thread.id.get,
              uriId = None,
              lastSeen = None,
              unread = unread,
              lastMsgFromOther = Some(message.id.get),
              lastNotification = notifJson,
              notificationUpdatedAt = message.createdAt,
              replyable = false
            ))
          }
          userId
        }
      }

      val notified = notificationAttempts collect { case Success(userId) => userId }
      messagingAnalytics.sentGlobalNotification(notified, message, thread, category)

      val errors = notificationAttempts collect { case Failure(ex) => ex }
      if (errors.size > 0) throw scala.collection.parallel.CompositeThrowable(errors)
    }
    message.id.get
  }

  private def buildMessageNotificationJson(
    message: Message,
    thread: MessageThread,
    messageWithBasicUser: MessageWithBasicUser,
    locator: String,
    unread: Boolean,
    originalAuthorIdx: Int,
    unseenAuthors: Int,
    numAuthors: Int,
    numMessages: Int,
    numUnread: Int,
    muted: Boolean): JsValue = {
    Json.obj(
      "id" -> message.externalId.id,
      "time" -> message.createdAt,
      "thread" -> thread.externalId.id,
      "text" -> message.messageText,
      "url" -> thread.nUrl,
      "title" -> thread.pageTitle,
      "author" -> messageWithBasicUser.user,
      "participants" -> messageWithBasicUser.participants,
      "locator" -> locator,
      "unread" -> unread,
      "category" -> NotificationCategory.User.MESSAGE.category,
      "firstAuthor" -> originalAuthorIdx,
      "authors" -> numAuthors, //number of people who have sent messages in this conversation
      "messages" -> numMessages, //total number of messages in this conversation
      "unreadAuthors" -> unseenAuthors, //number of people in 'participants' whose messages user hasn't seen yet
      "unreadMessages" -> numUnread,
      "muted" -> muted
    )
  }

  def sendPushNotification(userId: Id[User], notification: PushNotification): Int = {
    val deviceCount = urbanAirship.notifyUser(userId, notification)
    messagingAnalytics.sentPushNotification(userId, notification)
    deviceCount
  }

  def sendNotificationForMessage(userId: Id[User], message: Message, thread: MessageThread, messageWithBasicUser: MessageWithBasicUser, orderedActivityInfo: Seq[UserThreadActivity]): Unit = {
    SafeFuture {
      val authorActivityInfos = orderedActivityInfo.filter(_.lastActive.isDefined)
      val lastSeenOpt: Option[DateTime] = orderedActivityInfo.filter(_.userId == userId).head.lastSeen
      val unseenAuthors: Int = lastSeenOpt match {
        case Some(lastSeen) => authorActivityInfos.count(_.lastActive.get.isAfter(lastSeen))
        case None => authorActivityInfos.length
      }
      val (numMessages: Int, numUnread: Int, muted: Boolean) = db.readOnlyMaster { implicit session =>
        val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.id.get, lastSeenOpt)
        val muted = userThreadRepo.isMuted(userId, thread.id.get)
        (numMessages, numUnread, muted)
      }

      val notifJson = buildMessageNotificationJson(
        message = message,
        thread = thread,
        messageWithBasicUser = messageWithBasicUser,
        locator = thread.deepLocator.value,
        unread = true,
        originalAuthorIdx = authorActivityInfos.filter(_.started).zipWithIndex.head._2,
        unseenAuthors = unseenAuthors,
        numAuthors = authorActivityInfos.length,
        numMessages = numMessages,
        numUnread = numUnread,
        muted = muted)

      db.readWrite(attempts = 2) { implicit session =>
        userThreadRepo.setNotification(userId, thread.id.get, message, notifJson, !muted)
      }

      messagingAnalytics.sentNotificationForMessage(userId, message, thread, muted)
      shoebox.createDeepLink(message.from.asUser, userId, thread.uriId.get, thread.deepLocator)

      val unreadCount = db.readOnlyMaster { implicit session =>
        userThreadRepo.getUnreadUnmutedThreadCount(userId)
      }

      notificationRouter.sendToUser(userId, Json.arr("notification", notifJson))
      notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", unreadCount))

      if (!muted) {
        val sender = messageWithBasicUser.user match {
          case Some(bu: BasicUser) => bu.firstName + ": "
          case Some(bnu: BasicNonUser) => bnu.firstName.getOrElse(bnu.id) + ": "
          case _ => ""
        }
        val notifText = sender + MessageFormatter.toText(message.messageText)
        val sound = if (numMessages > 1) UrbanAirship.MoreMessageNotificationSound else UrbanAirship.DefaultNotificationSound
        val notification = MessageThreadPushNotification(thread.externalId, unreadCount, Some(trimAtBytes(notifText, 128, UTF_8)), Some(sound))
        sendPushNotification(userId, notification)
      }
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(userId), Notification(thread.id.get, message.id.get))
  }

  private def trimAtBytes(str: String, len: Int, charset: Charset) = { //Conner's Algorithm
    val outBuf = ByteBuffer.wrap(new Array[Byte](len))
    val inBuf = CharBuffer.wrap(str.toCharArray())
    charset.newEncoder().encode(inBuf, outBuf, true)
    new String(outBuf.array, 0, outBuf.position(), charset)
  }

  //for a given user and thread make sure the notification is correct
  private def recreateNotificationForAddedParticipant(userId: Id[User], thread: MessageThread): Future[JsValue] = {
    val message = db.readOnlyMaster { implicit session => messageRepo.getLatest(thread.id.get) }

    val participantSet = thread.participants.map(_.allUsers).getOrElse(Set())
    new SafeFuture(shoebox.getBasicUsers(participantSet.toSeq).map { id2BasicUser =>

      val (numMessages: Int, numUnread: Int, threadActivity: Seq[UserThreadActivity]) = db.readOnlyMaster { implicit session =>
        val (numMessages, numUnread) = messageRepo.getMessageCounts(thread.id.get, Some(message.createdAt))
        val threadActivity = userThreadRepo.getThreadActivity(thread.id.get).sortBy { uta =>
          (-uta.lastActive.getOrElse(START_OF_TIME).getMillis, uta.id.id)
        }
        (numMessages, numUnread, threadActivity)
      }

      val originalAuthor = threadActivity.filter(_.started).zipWithIndex.head._2
      val numAuthors = threadActivity.count(_.lastActive.isDefined)

      val nonUsers = thread.participants.map(_.allNonUsers.map(NonUserParticipant.toBasicNonUser)).getOrElse(Set.empty)

      val orderedMessageWithBasicUser = MessageWithBasicUser(
        message.externalId,
        message.createdAt,
        message.messageText,
        message.source,
        None,
        message.sentOnUrl.getOrElse(""),
        thread.nUrl.getOrElse(""), //TODO Stephen: This needs to change when we have detached threads
        message.from match {
          case MessageSender.User(id) => Some(id2BasicUser(id))
          case MessageSender.NonUser(nup) => Some(NonUserParticipant.toBasicNonUser(nup))
          case _ => None
        },
        threadActivity.map { ta => id2BasicUser(ta.userId) } ++ nonUsers
      )

      val notifJson = buildMessageNotificationJson(
        message = message,
        thread = thread,
        messageWithBasicUser = orderedMessageWithBasicUser,
        locator = "/messages/" + thread.externalId,
        unread = true,
        originalAuthorIdx = originalAuthor,
        unseenAuthors = numAuthors,
        numAuthors = numAuthors,
        numMessages = numMessages,
        numUnread = numUnread,
        muted = false
      )

      db.readWrite(attempts = 2) { implicit session =>
        userThreadRepo.setNotification(userId, thread.id.get, message, notifJson, true)
      }

      messagingAnalytics.sentNotificationForMessage(userId, message, thread, false)
      shoebox.createDeepLink(message.from.asUser, userId, thread.uriId.get, thread.deepLocator)

      notifJson
    })
  }

  def setAllNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting all Notifications as read for user $userId.")
    db.readWrite(attempts = 2) { implicit session => userThreadRepo.markAllRead(userId, None) }
  }

  def setSystemNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting System Notifications as read for user $userId")
    db.readWrite(attempts = 2) { implicit session => userThreadRepo.markAllRead(userId, Some(true)) }
  }

  def setMessageNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting Messaging Notifications as read for user $userId")
    db.readWrite(attempts = 2) { implicit session => userThreadRepo.markAllRead(userId, Some(false)) }
  }

  def setAllNotificationsReadBefore(user: Id[User], messageId: ExternalId[Message], unreadCount: Int): DateTime = {
    val message = db.readWrite(attempts = 2) { implicit session =>
      val message = messageRepo.get(messageId)
      userThreadRepo.markAllReadAtOrBefore(user, message.createdAt)
      message
    }
    notificationRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadCount))
    val notification = MessageThreadPushNotification(message.threadExtId, unreadCount, None, None)
    sendPushNotification(user, notification)
    message.createdAt
  }

  def getUnreadThreadNotifications(userId: Id[User]): Seq[Notification] = {
    db.readOnlyReplica { implicit session =>
      userThreadRepo.getUnreadThreadNotifications(userId)
    }
  }

  def getSendableNotification(userId: Id[User], threadExtId: ExternalId[MessageThread], includeUriSummary: Boolean): Future[NotificationJson] = {
    notificationJsonMaker.makeOne(db.readOnlyReplica { implicit session =>
      val thread = threadRepo.get(threadExtId)
      userThreadRepo.getRawNotification(userId, thread.id.get)
    }, includeUriSummary)
  }

  def getLatestSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean, filterByReplyable: Option[Boolean] = None): Future[Seq[NotificationJson]] = {
    notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getLatestRawNotifications(userId, howMany, filterByReplyable)
    }, includeUriSummary)
  }

  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean, filterByReplyable: Option[Boolean] = None): Future[Seq[NotificationJson]] = {
    notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getRawNotificationsBefore(userId, time, howMany, filterByReplyable)
    }, includeUriSummary)
  }

  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[(Seq[NotificationJson], Int)] = {
    val noticesFuture = notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getLatestUnreadRawNotifications(userId, howMany)
    }, includeUriSummary)
    new SafeFuture(noticesFuture map { notices =>
      val numTotal = if (notices.length < howMany) {
        notices.length
      } else {
        db.readOnlyReplica { implicit session =>
          userThreadRepo.getUnreadThreadCount(userId)
        }
      }
      (notices, numTotal)
    })
  }

  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getUnreadRawNotificationsBefore(userId, time, howMany)
    }, includeUriSummary)
  }

  // given a set of notifications (unread)
  // group notifications by category (we want "somebody followed your library" & to same library)
  //

  def getLatestSentSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getLatestRawNotificationsForStartedThreads(userId, howMany)
    }, includeUriSummary)
  }

  def getSentSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
      userThreadRepo.getRawNotificationsForStartedThreadsBefore(userId, time, howMany)
    }, includeUriSummary)
  }

  def getLatestSendableNotificationsForPage(userId: Id[User], url: String, howMany: Int, includeUriSummary: Boolean): Future[(String, Seq[NotificationJson], Int, Int)] = {
    new SafeFuture(shoebox.getNormalizedUriByUrlOrPrenormalize(url) flatMap {
      case Left(nUri) =>
        val noticesFuture = notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
          userThreadRepo.getLatestRawNotificationsForUri(userId, nUri.id.get, howMany)
        }, includeUriSummary)
        new SafeFuture(noticesFuture map { notices =>
          val (numTotal, numUnreadUnmuted): (Int, Int) = if (notices.length < howMany) {
            (notices.length, notices.count { n =>
              (n.obj \ "unread").asOpt[Boolean].getOrElse(false) &&
                !(n.obj \ "muted").asOpt[Boolean].getOrElse(false)
            })
          } else {
            db.readOnlyReplica { implicit session =>
              userThreadRepo.getThreadCountsForUri(userId, nUri.id.get)
            }
          }
          (nUri.url, notices, numTotal, numUnreadUnmuted)
        })
      case Right(prenormalizedUrl) =>
        Promise.successful(prenormalizedUrl, Seq.empty, 0, 0).future
    })
  }

  def getSendableNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    new SafeFuture(shoebox.getNormalizedURIByURL(url) flatMap {
      case Some(nUri) =>
        notificationJsonMaker.make(db.readOnlyReplica { implicit session =>
          userThreadRepo.getRawNotificationsForUriBefore(userId, nUri.id.get, time, howMany)
        }, includeUriSummary)
      case _ => Promise.successful(Seq.empty).future
    })
  }

  def connectedSockets: Int = notificationRouter.connectedSockets

  def notifyUserAboutMuteChange(userId: Id[User], threadId: ExternalId[MessageThread], mute: Boolean) = {
    notificationRouter.sendToUser(userId, Json.arr("thread_muted", threadId.id, mute))
  }

  def getUnreadNotifications(userId: Id[User], howMany: Int): Seq[UserThreadView] = {
    db.readOnlyReplica { implicit s =>
      val userThreads = userThreadRepo.getLatestUnreadUnmutedThreads(userId, howMany)
      userThreads.map { userThread =>
        val threadId = userThread.threadId
        val messageThread = threadRepo.get(threadId)

        val messagesSinceLastSeen = userThread.lastSeen map { seenAt =>
          messageRepo.getAfter(threadId, seenAt)
        } getOrElse {
          messageRepo.get(threadId, 0)
        }

        UserThread.toUserThreadView(userThread, messagesSinceLastSeen, messageThread)
      }
    }
  }
}

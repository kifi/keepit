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

class NotificationDeliveryCommander @Inject() (
    threadRepo: MessageThreadRepo,
    userThreadRepo: UserThreadRepo,
    nonUserThreadRepo: NonUserThreadRepo,
    messageRepo: MessageRepo,
    threadNotifBuilder: MessageThreadNotificationBuilder,
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

  def updateEmailParticipantThreads(thread: MessageThread, newMessage: ElizaMessage): Unit = {
    val emailParticipants = thread.participants.allNonUsers.collect { case emailParticipant: NonUserEmailParticipant => emailParticipant.address }
    val emailSenderOption = newMessage.from.asNonUser.collect {
      case emailSender: NonUserEmailParticipant => emailSender.address
    }
    val emailRecipients = emailParticipants -- emailSenderOption
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

  def notifyEmailParticipants(thread: MessageThread): Unit = {
    emailCommander.notifyEmailUsers(thread)
  }

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
        val messageWithBasicUser = basicMessageCommander.getMessageWithBasicUser(message, thread, basicUsers)
        thread.participants.allUsers.par.foreach { userId =>
          sendToUser(userId, Json.arr("message", thread.pubKeepId, messageWithBasicUser))
          sendToUser(userId, Json.arr("thread_participants", thread.pubKeepId, participants))
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

  def getNotificationsByUser(userId: Id[User], utq: UserThreadQuery, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = {
    val uts = db.readOnlyReplica { implicit session =>
      userThreadRepo.getThreadsForUser(userId, utq)
    }
    utq.onUri.collect {
      case nUriId if uts.length < utq.limit =>
        shoebox.getRelevantKeepsByUserAndUri(userId, nUriId, utq.beforeTime, utq.limit - uts.length)
    }.getOrElse(Future.successful(Seq.empty)).flatMap { otherKeeps =>
      val keeps = uts.map { ut =>
        (ut.keepId, ut.unread, ut.uriId)
      } ++ otherKeeps.collect {
        case b if !uts.exists(_.keepId == b.id) =>
          (b.id, false, NormalizedURI.decodePublicId(b.keep.uriId).toOption)
      }
      val notifJsonsByThreadFut = threadNotifBuilder.buildForKeeps(userId, keeps.map(_._1).toSet)

      notifJsonsByThreadFut.flatMap { notifJsonsByKeep =>
        val inputs = keeps.flatMap { b => notifJsonsByKeep.get(b._1).map(notif => (Json.toJson(notif), b._2, b._3)) }
        notificationJsonMaker.make(inputs, includeUriSummary)
      }
    }
  }

  def sendNotificationForMessage(userId: Id[User], message: ElizaMessage, thread: MessageThread, sender: Option[BasicUserLikeEntity], orderedActivityInfo: Seq[UserThreadActivity], forceOverwrite: Boolean = false): Unit = SafeFuture {
    val lastSeenOpt: Option[DateTime] = orderedActivityInfo.find(_.userId == userId).flatMap(_.lastSeen)
    val (msgCount, muted) = db.readOnlyMaster { implicit session =>
      val msgCount = messageRepo.getMessageCounts(thread.keepId, lastSeenOpt)
      val muted = userThreadRepo.isMuted(userId, thread.keepId)
      (msgCount, muted)
    }
    val precomputedInfo = MessageThreadNotificationBuilder.PrecomputedInfo.BuildForKeep(
      thread = Some(thread),
      lastMsg = Some(Some(message)),
      muted = Some(muted),
      threadActivity = Some(orderedActivityInfo),
      msgCount = Some(msgCount)
    )
    val notifFut = threadNotifBuilder.buildForKeep(userId, thread.keepId, precomputed = Some(precomputedInfo)).map(_.get).map(_.copy(forceOverwrite = forceOverwrite))

    messagingAnalytics.sentNotificationForMessage(userId, message, thread, muted)
    shoebox.createDeepLink(message.from.asUser, userId, thread.uriId, thread.deepLocator)

    val (unreadMessages, unreadNotifications) = db.readOnlyMaster { implicit session =>
      (userThreadRepo.getUnreadThreadCounts(userId).unmuted, notificationRepo.getUnreadNotificationsCount(Recipient.fromUser(userId)))
    }

    notifFut.foreach { notif =>
      notificationRouter.sendToUser(userId, Json.arr("notification", notif))
      notificationRouter.sendToUser(userId, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
    }

    //This is mostly for testing and monitoring
    notificationRouter.sendNotification(Some(userId), UserThreadNotification(message.keepId, message.id.get))
  }

  def sendPushNotificationForMessage(userId: Id[User], message: ElizaMessage, sender: Option[BasicUserLikeEntity], orderedActivityInfo: Seq[UserThreadActivity]): Unit = SafeFuture {
    val lastSeenOpt: Option[DateTime] = orderedActivityInfo.find(_.userId == userId).flatMap(_.lastSeen)
    val (msgCount, muted, unreadThreads, unreadNotifs) = db.readOnlyMaster { implicit session =>
      val msgCount = messageRepo.getMessageCounts(message.keepId, lastSeenOpt)
      val muted = userThreadRepo.isMuted(userId, message.keepId)
      val unreadThreads = userThreadRepo.getUnreadThreadCounts(userId).unmuted
      val unreadNotifications = notificationRepo.getUnreadNotificationsCount(Recipient.fromUser(userId))
      (msgCount, muted, unreadThreads, unreadNotifications)
    }
    if (!message.from.asUser.contains(userId) && !muted) {
      val senderStr = sender match {
        case Some(BasicUserLikeEntity.user(bu)) => bu.firstName + ": "
        case Some(BasicUserLikeEntity.nonUser(bnu)) => bnu.firstName.getOrElse(bnu.id) + ": "
        case _ => ""
      }
      val notifText = senderStr + MessageFormatter.toText(message.messageText)
      val sound = if (msgCount.total > 1) MobilePushNotifier.MoreMessageNotificationSound else MobilePushNotifier.DefaultNotificationSound
      val notification = MessageThreadPushNotification(message.pubKeepId, unreadThreads + unreadNotifs, Some(trimAtBytes(notifText, 128, UTF_8)), Some(sound))
      sendPushNotification(userId, notification)
    }
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
      notificationRepo.setAllRead(Recipient.fromUser(userId))
    }
  }

  def setSystemNotificationsRead(userId: Id[User]): Unit = {
    log.info(s"Setting System Notifications as read for user $userId")
    db.readWrite(attempts = 2) { implicit session =>
      notificationRepo.setAllRead(Recipient.fromUser(userId))
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
      notificationRepo.setAllReadBefore(Recipient.fromUser(user), message.createdAt)
      message
    }
    notificationRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
    val notification = MessageThreadPushNotification(message.pubKeepId, unreadMessages + unreadNotifications, None, None)
    sendPushNotification(user, notification)
    message.createdAt
  }

  def getSendableNotification(userId: Id[User], keepId: Id[Keep], includeUriSummary: Boolean): Future[Option[NotificationJson]] = {
    getNotificationsByUser(userId, UserThreadQuery(keepIds = Some(Set(keepId)), limit = 1), includeUriSummary).map(_.headOption)
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
    val unreadNotificationCount = notificationRepo.getUnreadNotificationsCount(Recipient.fromUser(userId))
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

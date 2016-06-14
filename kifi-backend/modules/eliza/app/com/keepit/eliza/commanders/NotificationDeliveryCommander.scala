package com.keepit.eliza.commanders

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.{ ByteBuffer, CharBuffer }

import com.google.inject.{ Singleton, ImplementedBy, Inject }
import com.keepit.common.akka.SafeFuture
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.common.util.{ DescriptionElements, Ord }
import com.keepit.discussion.Message
import com.keepit.eliza._
import com.keepit.eliza.commanders.MessageThreadNotificationBuilder.PrecomputedInfo
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model.{ UserThread, UserThreadActivity, UserThreadNotification, _ }
import com.keepit.eliza.util.MessageFormatter
import com.keepit.model._
import com.keepit.notify.model.Recipient
import com.keepit.realtime._
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.social.{ BasicUser, BasicUserLikeEntity }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.libs.functional.syntax._
import com.keepit.common.core._

import scala.concurrent.{ ExecutionContext, Future }

case class UserThreadQuery(
  keepIds: Option[Set[Id[Keep]]] = None,
  beforeTime: Option[DateTime] = None,
  onlyStartedBy: Option[Id[User]] = None,
  onlyUnread: Option[Boolean] = None,
  onUri: Option[Id[NormalizedURI]] = None,
  limit: Int)

case class UnreadThreadCounts(total: Int, unmuted: Int)

@ImplementedBy(classOf[NotificationDeliveryCommanderImpl])
trait NotificationDeliveryCommander {
  // todo: For each method here, remove if no one's calling it externally, and set as private in the implementation
  def updateEmailParticipantThreads(thread: MessageThread, newMessage: ElizaMessage): Unit
  def notifyEmailParticipants(thread: MessageThread): Unit
  def notifyThreadAboutParticipantDiff(adderUserId: Id[User], diff: KeepRecipientsDiff, thread: MessageThread, basicEvent: BasicKeepEvent): Unit
  def notifyMessage(userId: Id[User], keepId: PublicId[Keep], message: MessageWithBasicUser): Unit
  def notifyRead(userId: Id[User], keepId: Id[Keep], messageIdOpt: Option[Id[ElizaMessage]], nUrl: String, readAt: DateTime): Unit
  def notifyUnread(userId: Id[User], keepId: Id[Keep], messageIdOpt: Option[Id[ElizaMessage]], nUrl: String, readAt: DateTime): Unit
  def notifyUnreadCount(userId: Id[User]): Unit
  def notifyRemoveThread(userId: Id[User], keepId: Id[Keep]): Unit
  def sendToUser(userId: Id[User], data: JsArray): Unit
  def sendKeepEvent(userId: Id[User], keepId: PublicId[Keep], event: BasicKeepEvent): Unit
  def sendKeepRecipients(usersToSend: Set[Id[User]], keepId: PublicId[Keep], basicUsers: Set[BasicUser], basicLibraries: Set[BasicLibrary], emails: Set[EmailAddress]): Unit
  def sendUserPushNotification(userId: Id[User], message: String, recipientUserId: ExternalId[User], username: Username, pictureUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: UserPushNotificationCategory): Future[Int]
  def sendLibraryPushNotification(userId: Id[User], message: String, libraryId: Id[Library], libraryUrl: String, pushNotificationExperiment: PushNotificationExperiment, category: LibraryPushNotificationCategory, force: Boolean): Future[Int]
  def sendGeneralPushNotification(userId: Id[User], message: String, pushNotificationExperiment: PushNotificationExperiment, category: SimplePushNotificationCategory, force: Boolean): Future[Int]
  def sendOrgPushNotification(request: OrgPushNotificationRequest): Future[Int]
  def getNotificationsByUser(userId: Id[User], utq: UserThreadQuery, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def sendNotificationForMessage(userId: Id[User], message: ElizaMessage, thread: MessageThread, sender: Option[BasicUserLikeEntity], orderedActivityInfo: Seq[UserThreadActivity], forceOverwrite: Boolean = false): Unit
  def sendPushNotificationForMessage(userId: Id[User], message: ElizaMessage, sender: Option[BasicUserLikeEntity], orderedActivityInfo: Seq[UserThreadActivity]): Unit
  def setAllNotificationsRead(userId: Id[User]): Unit
  def setSystemNotificationsRead(userId: Id[User]): Unit
  def setMessageNotificationsRead(userId: Id[User]): Unit
  def setAllNotificationsReadBefore(user: Id[User], messageId: Id[ElizaMessage], unreadMessages: Int, unreadNotifications: Int): DateTime
  def getSendableNotification(userId: Id[User], keepId: Id[Keep], includeUriSummary: Boolean): Future[Option[NotificationJson]]
  def getUnreadThreadNotifications(userId: Id[User]): Seq[UserThreadNotification]
  def getLatestSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def getSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def getLatestUnreadSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[(Seq[NotificationJson], Int)]
  def getUnreadSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def getLatestSentSendableNotifications(userId: Id[User], howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def getSentSendableNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def getLatestSendableNotificationsForPage(userId: Id[User], url: String, howMany: Int, includeUriSummary: Boolean): Future[(String, Seq[NotificationJson], Int, Int)]
  def getSendableNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]]
  def connectedSockets: Int
  def notifyUserAboutMuteChange(userId: Id[User], keepId: PublicId[Keep], mute: Boolean)
  def getUnreadUnmutedThreads(userId: Id[User], howMany: Int): Seq[UserThreadView]
  def getUnreadCounts(userId: Id[User]): (Int, Int, Int)
  def getTotalUnreadUnmutedCount(userId: Id[User]): Int
}

@Singleton
class NotificationDeliveryCommanderImpl @Inject() (
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
    implicit val executionContext: ExecutionContext) extends NotificationDeliveryCommander with Logging {

  def updateEmailParticipantThreads(thread: MessageThread, newMessage: ElizaMessage): Unit = {
    val emailParticipants = thread.participants.allEmails
    val emailSenderOption = newMessage.from.asNonUser.map(_.address)
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

  def notifyThreadAboutParticipantDiff(adderUserId: Id[User], diff: KeepRecipientsDiff, thread: MessageThread, basicEvent: BasicKeepEvent): Unit = {
    new SafeFuture(for {
      (basicUsers, basicLibraries, emails) <- shoebox.getRecipientsOnKeep(thread.keepId)
      author <- basicUsers.get(adderUserId).map(Future.successful).getOrElse {
        shoebox.getBasicUsers(Seq(adderUserId)).map(_.getOrElse(adderUserId, throw new Exception(s"Action taken by $adderUserId cannot be displayed because user does not exist")))
      }
    } yield {
      val theTitle: String = thread.pageTitle.getOrElse("New conversation")
      val participants: Seq[BasicUserLikeEntity] =
        basicUsers.values.toSeq.map(u => BasicUserLikeEntity(u)) ++
          thread.participants.allNonUsers.toSeq.map(nu => BasicUserLikeEntity(EmailParticipant.toBasicNonUser(nu)))
      val notificationJson = Json.obj(
        "id" -> basicEvent.id,
        "time" -> basicEvent.timestamp,
        "thread" -> thread.pubKeepId,
        "text" -> DescriptionElements.formatPlain(basicEvent.header),
        "url" -> thread.url,
        "title" -> theTitle,
        "author" -> author,
        "participants" -> participants,
        "locator" -> thread.deepLocator,
        "unread" -> true,
        "category" -> NotificationCategory.User.MESSAGE.category
      )

      val precomputedInfo = PrecomputedInfo.BuildForEvent(Some(thread), Some(basicUsers))
      val threadNotifsByUserFut = threadNotifBuilder.buildForUsersFromEvent(diff.users.added, thread.keepId, basicEvent, author, Some(precomputedInfo))

      threadNotifsByUserFut.foreach { threadNotifsByUser =>
        (diff.users.added - adderUserId).foreach { userId =>
          sendToUser(userId, Json.arr("notification", notificationJson, threadNotifsByUser(userId)))
        }
      }

      thread.participants.allUsers.par.foreach { userId =>
        sendToUser(userId, Json.arr("event", thread.pubKeepId, basicEvent))
        sendToUser(userId, Json.arr("thread_participants", thread.pubKeepId, participants))
      }

      diff.users.removed.foreach { userId =>
        sendToUser(userId, Json.arr("removed_from_thread", thread.pubKeepId))
      }

      val pushNotifText = s"${author.fullName} sent ${thread.pageTitle.getOrElse("you a page")}"
      (diff.users.added - adderUserId).foreach { userId =>
        sendPushNotificationForMessageThread(userId, thread.keepId, pushNotifText, lastSeenThread = None)
      }
      sendKeepRecipients(thread.participants.allUsers, thread.pubKeepId, basicUsers.values.toSet, basicLibraries.values.toSet, emails)
      emailCommander.notifyAddedEmailUsers(thread, diff.emails.added.toSeq)
    })
  }

  def notifyMessage(userId: Id[User], keepId: PublicId[Keep], message: MessageWithBasicUser): Unit =
    sendToUser(userId, Json.arr("message", keepId, message))

  def notifyRead(userId: Id[User], keepId: Id[Keep], messageIdOpt: Option[Id[ElizaMessage]], nUrl: String, readAt: DateTime): Unit = {
    // TODO(ryan): stop manually forcing the date to go to millis, fix the Json formatter
    val messagePubIdOpt = messageIdOpt.map(msgId => Message.publicId(ElizaMessage.toCommonId(msgId)))
    sendToUser(userId, Json.arr("message_read", nUrl, Keep.publicId(keepId), readAt.getMillis, messagePubIdOpt))
    notifyUnreadCount(userId, keepId)
  }

  def notifyUnread(userId: Id[User], keepId: Id[Keep], messageIdOpt: Option[Id[ElizaMessage]], nUrl: String, readAt: DateTime): Unit = {
    val messagePubIdOpt = messageIdOpt.map(msgId => Message.publicId(ElizaMessage.toCommonId(msgId)))
    sendToUser(userId, Json.arr("message_unread", nUrl, Keep.publicId(keepId), readAt, messagePubIdOpt))
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

  def sendKeepEvent(userId: Id[User], keepId: PublicId[Keep], event: BasicKeepEvent): Unit = sendToUser(userId, Json.arr("event", keepId, event))

  def sendKeepRecipients(usersToSend: Set[Id[User]], keepId: PublicId[Keep], basicUsers: Set[BasicUser], basicLibraries: Set[BasicLibrary], emails: Set[EmailAddress]): Unit = {
    usersToSend.par.foreach { userId =>
      sendToUser(userId, Json.arr("thread_recipients", keepId, basicUsers.toSeq.sortBy(_.firstName), emails.toSeq.sortBy(_.address), basicLibraries.toSeq.sortBy(_.name)))
    }
  }

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
      userThreadRepo.getThreadsForUser(userId, utq.copy(limit = utq.limit))
    }

    val keepsWithThreads = uts.map { ut => (ut.keepId, ut.unread) }
    val moreKeeps = for {
      keepIds <- utq.keepIds.toSeq
      keepId <- keepIds if !uts.exists(_.keepId == keepId)
    } yield (keepId, false)

    val keeps = keepsWithThreads ++ moreKeeps

    val userThreadByKeepId = uts.groupBy(_.keepId).map { case (keepId, Seq(ut)) => keepId -> ut }
    val precomputedInfo = PrecomputedInfo.BuildForKeeps(userThreadById = Some(userThreadByKeepId))

    threadNotifBuilder.buildForKeeps(userId, keeps.map(_._1).toSet, Some(precomputedInfo)).flatMap { notifByKeep =>
      val validNotifs = for {
        (keepId, unread) <- keeps
        notif <- notifByKeep.get(keepId) if utq.beforeTime.forall(notif.time isBefore)
      } yield (notif, unread, keepId)
      val sortedRawNotifs = validNotifs.sortBy(_._1.time)(Ord.descending).map { case (notif, unread, uriId) => (Json.toJson(notif), unread, uriId) }
      notificationJsonMaker.make(sortedRawNotifs, includeUriSummary)
    }
  }

  def sendNotificationForMessage(userId: Id[User], message: ElizaMessage, thread: MessageThread, sender: Option[BasicUserLikeEntity], orderedActivityInfo: Seq[UserThreadActivity], forceOverwrite: Boolean = false): Unit = SafeFuture {
    val lastSeenOpt: Option[DateTime] = orderedActivityInfo.find(_.userId == userId).flatMap(_.lastSeen)
    val (msgCount, userThreadOpt) = db.readOnlyMaster { implicit session =>
      val msgCount = messageRepo.getMessageCounts(thread.keepId, lastSeenOpt)
      val userThreadOpt = userThreadRepo.getUserThread(userId, thread.keepId)
      (msgCount, userThreadOpt)
    }
    val precomputedInfo = MessageThreadNotificationBuilder.PrecomputedInfo.BuildForKeep(
      thread = Some(thread),
      userThread = userThreadOpt,
      lastMsg = Some(Some(message)),
      threadActivity = Some(orderedActivityInfo),
      msgCount = Some(msgCount)
    )
    val notifFut = threadNotifBuilder.buildForKeep(userId, thread.keepId, precomputed = Some(precomputedInfo)).map(_.get).map(_.copy(forceOverwrite = forceOverwrite))

    messagingAnalytics.sentNotificationForMessage(userId, message, thread, userThreadOpt.exists(_.muted))
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
    val muted = db.readOnlyMaster(implicit session => userThreadRepo.isMuted(userId, message.keepId))
    if (!message.from.asUser.contains(userId) && !muted) {
      val senderStr = sender match {
        case Some(BasicUserLikeEntity.user(bu)) => bu.firstName + ": "
        case Some(BasicUserLikeEntity.nonUser(bnu)) => bnu.firstName.getOrElse(bnu.id) + ": "
        case _ => ""
      }
      val notifText = senderStr + MessageFormatter.toText(message.messageText)
      sendPushNotificationForMessageThread(userId, message.keepId, notifText, lastSeenOpt)
    }
  }

  def sendPushNotificationForMessageThread(userId: Id[User], keepId: Id[Keep], text: String, lastSeenThread: Option[DateTime]): Unit = SafeFuture {
    val (msgCount, unreadThreads, unreadNotifs) = db.readOnlyMaster { implicit s =>
      val msgCount = messageRepo.getMessageCounts(keepId, afterOpt = lastSeenThread)
      val unreadThreads = userThreadRepo.getUnreadThreadCounts(userId).unmuted
      val unreadNotifs = notificationRepo.getUnreadNotificationsCount(Recipient.fromUser(userId))
      (msgCount, unreadThreads, unreadNotifs)
    }

    val sound = if (msgCount.total > 1) MobilePushNotifier.MoreMessageNotificationSound else MobilePushNotifier.DefaultNotificationSound
    val notification = MessageThreadPushNotification(Keep.publicId(keepId), unreadThreads + unreadNotifs, Some(trimAtBytes(text, 128, UTF_8)), Some(sound))
    sendPushNotification(userId, notification)
  }

  private def trimAtBytes(str: String, len: Int, charset: Charset) = { //Conner's Algorithm
    val outBuf = ByteBuffer.wrap(new Array[Byte](len))
    val inBuf = CharBuffer.wrap(str.toCharArray)
    charset.newEncoder().encode(inBuf, outBuf, true)
    new String(outBuf.array, 0, outBuf.position(), charset)
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
    getNotificationsByUser(userId, UserThreadQuery(onlyUnread = Some(true), limit = howMany), includeUriSummary).map { notices =>
      val numTotal = if (notices.length < howMany) {
        notices.length
      } else {
        db.readOnlyReplica { implicit session =>
          userThreadRepo.getUnreadThreadCounts(userId).total
        }
      }
      (notices, numTotal)
    }
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

  private def getNotificationsForUriId(userId: Id[User], uriId: Id[NormalizedURI], howMany: Int, before: Option[DateTime], includeUriSummary: Boolean): Future[(Set[Id[Keep]], Seq[NotificationJson])] = {
    shoebox.getPersonalKeepRecipientsOnUris(userId, Set(uriId), excludeAccess = Some(LibraryAccess.READ_ONLY)).flatMap { keepsByUriId =>
      val keepIds = keepsByUriId.getOrElse(uriId, Set.empty).map(_.id)
      getNotificationsByUser(userId, UserThreadQuery(keepIds = Some(keepIds), onUri = Some(uriId), limit = howMany, beforeTime = before), includeUriSummary).imap((keepIds, _))
    }
  }

  def getLatestSendableNotificationsForPage(userId: Id[User], url: String, howMany: Int, includeUriSummary: Boolean): Future[(String, Seq[NotificationJson], Int, Int)] = new SafeFuture({
    shoebox.getNormalizedUriByUrlOrPrenormalize(url).flatMap {
      case Right(prenormalizedUrl) =>
        Future.successful(prenormalizedUrl, Seq.empty, 0, 0)
      case Left(nUri) =>
        getNotificationsForUriId(userId, nUri.id.get, howMany, None, includeUriSummary).map {
          case (keepIds, notices) =>
            val unreadCounts = db.readOnlyReplica { implicit session =>
              userThreadRepo.getThreadCountsForKeeps(userId, keepIds)
            }
            (nUri.url, notices, unreadCounts.total, unreadCounts.unmuted)
        }
    }
  })

  def getSendableNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int, includeUriSummary: Boolean): Future[Seq[NotificationJson]] = new SafeFuture({
    shoebox.getNormalizedURIByURL(url) flatMap {
      case Some(nUri) => getNotificationsForUriId(userId, nUri.id.get, howMany, Some(time), includeUriSummary).imap(_._2)
      case _ => Future.successful(Seq.empty)
    }
  })

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

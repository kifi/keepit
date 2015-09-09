package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.eliza.commanders.NotificationMessagingCommander.{ SubsetNotificationResults, FullNotificationResults, NotificationResultsForPage }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.User
import com.keepit.notify.delivery.NotificationJsonFormat
import com.keepit.notify.info.NotificationInfoGenerator
import com.keepit.notify.model.{ UserRecipient, Recipient }
import com.keepit.notify.model.event.NewMessage
import com.keepit.realtime.{ MobilePushNotifier, MessageThreadPushNotification }
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json }

import scala.concurrent.{ ExecutionContext, Future }

class NotificationMessagingCommander @Inject() (
    notificationCommander: NotificationCommander,
    notificationJsonFormat: NotificationJsonFormat,
    notificationInfoGenerator: NotificationInfoGenerator,
    pushNotifier: MobilePushNotifier,
    messageFetchingCommander: MessageFetchingCommander,
    notificationRepo: NotificationRepo,
    notificationItemRepo: NotificationItemRepo,
    db: Database,
    webSocketRouter: WebSocketRouter,
    messagingAnalytics: MessagingAnalytics,
    userThreadRepo: UserThreadRepo,
    messageThreadRepo: MessageThreadRepo,
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val executionContext: ExecutionContext) {

  private def messageThreadIdForNotif(notif: Notification): ExternalId[MessageThread] = db.readOnlyMaster { implicit session =>
    val userThread = userThreadRepo.getByNotificationId(notif.id.get)
    // messaging analytics expects a message thread id, for now
    userThread.fold(ExternalId[MessageThread](notif.externalId.id)) { userThread =>
      messageThreadRepo.get(userThread.threadId).externalId
    }
  }

  def sendUnreadNotifications(notif: Notification, recipient: Recipient): Unit = {
    val (unreadMessages, unreadNotifications) = db.readOnlyMaster { implicit session =>
      (notificationRepo.getUnreadEnabledNotificationsCountForKind(recipient, NewMessage.name),
        notificationRepo.getUnreadEnabledNotificationsCountExceptKind(recipient, NewMessage.name))
    }
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
        val pushNotif = MessageThreadPushNotification(ExternalId[MessageThread](notif.externalId.id), unreadMessages + unreadNotifications, None, None)
        pushNotifier.notifyUser(user, pushNotif, false)
      case _ =>
    }
  }

  def changeNotificationDisabled(userId: Id[User], notif: Notification, disabled: Boolean)(implicit context: HeimdalContext) = {
    val updated = notificationCommander.setNotificationDisabledTo(notif.id.get, disabled)
    if (updated) {
      webSocketRouter.sendToUser(userId, Json.arr("thread_muted", notif.externalId, disabled))
      messagingAnalytics.changedMute(userId, messageThreadIdForNotif(notif), disabled, context)
    }
  }

  def changeNotificationUnread(userId: Id[User], notif: Notification, item: NotificationItem, unread: Boolean)(implicit context: HeimdalContext): Unit = {
    val updated = notificationCommander.setNotificationUnreadTo(notif.id.get, unread)
    if (updated) {
      val nUrl = notificationCommander.getURI(notif.id.get)
      webSocketRouter.sendToUser(userId, Json.arr(if (unread) "message_unread" else "message_read", nUrl, notif.externalId, item.eventTime, item.externalId))
      if (unread) {
        messagingAnalytics.clearedNotification(userId, ExternalId[Message](item.externalId.id), ExternalId[MessageThread](notif.externalId.id), context)
      }
      sendUnreadNotifications(notif, Recipient(userId))
    }
  }

  def setNotificationsUnreadBefore(notif: Notification, recipient: Recipient, item: NotificationItem): Unit = {
    db.readWrite { implicit session =>
      notificationRepo.setAllReadBefore(recipient, item.eventTime)
    }
    sendUnreadNotifications(notif, recipient)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("all_notifications_visited", item.externalId, item.eventTime))
      case _ =>
    }
  }

  def getNotificationsForPage(userId: Id[User], url: String, howMany: Int, uriSummary: Boolean = false): Future[NotificationResultsForPage] = {
    val recipient = Recipient(userId)
    shoeboxServiceClient.getNormalizedUriByUrlOrPrenormalize(url) flatMap {
      case Left(nUri) =>
        val previous = db.readOnlyReplica { implicit session =>
          userThreadRepo.getLatestRawNotificationsForUri(userId, nUri.id.get, howMany)
        }
        notificationCommander.backfillLegacyNotificationsFor(userId, previous)
        val notifsForPage = db.readOnlyReplica { implicit session =>
          notificationRepo.getNotificationsForPage(recipient, nUri.id.get, howMany).map { notif =>
            NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
          }
        }
        val finalNotifsF = notificationInfoGenerator.generateInfo(notifsForPage).flatMap { infos =>
          Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
        }
        val resultsF = finalNotifsF.map { finalNotifs =>
          if (notifsForPage.length < howMany) {
            FullNotificationResults(finalNotifs)
          } else {
            val (numTotal, numUnread) = db.readOnlyReplica { implicit session =>
              userThreadRepo.getThreadCountsForUri(userId, nUri.id.get)
            }
            SubsetNotificationResults(finalNotifs, numTotal, numUnread)
          }
        }
        resultsF.map { results =>
          NotificationResultsForPage(nUri.url, results)
        }
      case Right(prenormalizedUrl) =>
        Future.successful(NotificationResultsForPage(
          pageUri = prenormalizedUrl,
          results = FullNotificationResults(Seq())
        ))
    }
  }

  def getNotificationsForPageBefore(userId: Id[User], url: String, time: DateTime, howMany: Int, uriSummary: Boolean = false): Future[Seq[NotificationWithJson]] = {
    val recipient = Recipient(userId)
    shoeboxServiceClient.getNormalizedUriByUrlOrPrenormalize(url) flatMap {
      case Left(nUri) =>
        val previous = db.readOnlyReplica { implicit session =>
          userThreadRepo.getRawNotificationsForUriBefore(userId, nUri.id.get, time, howMany)
        }
        notificationCommander.backfillLegacyNotificationsFor(userId, previous)
        val notifsForPage = db.readOnlyReplica { implicit session =>
          notificationRepo.getNotificationsForPageBefore(recipient, nUri.id.get, time, howMany).map { notif =>
            NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
          }
        }
        notificationInfoGenerator.generateInfo(notifsForPage).flatMap { infos =>
          Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
        }
      case Right(prenormalizedUrl) =>
        Future.successful(Seq())
    }
  }

  def getNotificationMessages(userId: Id[User], notifId: Id[Notification]): Future[(MessageThread, Seq[MessageWithBasicUser])] = {
    db.readOnlyReplica { implicit session =>
      notificationCommander.getMessageThread(notifId)
    }.fold(
      Future.failed[(MessageThread, Seq[MessageWithBasicUser])](new Exception(s"Notification $notifId does not have a message thread"))
    ) { messageThread =>
        messageFetchingCommander.getThreadMessagesWithBasicUser(userId, messageThread)
      }
  }

  def getNotificationsForSentMessages(userId: Id[User], howMany: Int, uriSummary: Boolean = false): Future[Seq[NotificationWithJson]] = {
    val recipient = Recipient(userId)
    val previous = db.readOnlyReplica { implicit session =>
      userThreadRepo.getLatestRawNotificationsForStartedThreads(userId, howMany)
    }
    notificationCommander.backfillLegacyNotificationsFor(userId, previous)
    val sentNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getNotificationsForSentMessages(recipient, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(sentNotifs).flatMap { infos =>
      Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
    }
  }

  def getNotificationsForSentMessagesBefore(userId: Id[User], time: DateTime, howMany: Int, uriSummary: Boolean = false): Future[Seq[NotificationWithJson]] = {
    val recipient = Recipient(userId)
    val previous = db.readOnlyReplica { implicit session =>
      userThreadRepo.getLatestRawNotificationsForStartedThreads(userId, howMany)
    }
    notificationCommander.backfillLegacyNotificationsFor(userId, previous)
    val sentNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getNotificationsForSentMessagesBefore(recipient, time, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(sentNotifs).flatMap { infos =>
      Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
    }
  }

}

object NotificationMessagingCommander {

  class NotificationResults(val results: Seq[NotificationWithJson]) {
    def numTotal = results.size
    def numUnread = results.count(_.notification.unread)
  }

  case class FullNotificationResults(override val results: Seq[NotificationWithJson])
    extends NotificationResults(results)

  case class SubsetNotificationResults(override val results: Seq[NotificationWithJson],
    override val numTotal: Int,
    override val numUnread: Int) extends NotificationResults(results)

  case class NotificationResultsForPage(pageUri: String, results: NotificationResults)

}

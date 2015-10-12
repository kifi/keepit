package com.keepit.eliza.commanders

import com.google.inject.Inject
import com.keepit.common.db.slick.Database
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.eliza.commanders.NotificationMessagingCommander.{ NotificationResults, SubsetNotificationResults, FullNotificationResults, NotificationResultsForPage }
import com.keepit.eliza.controllers.WebSocketRouter
import com.keepit.eliza.model._
import com.keepit.heimdal.HeimdalContext
import com.keepit.model.User
import com.keepit.notify.delivery.NotificationJsonFormat
import com.keepit.notify.info.NotificationInfoGenerator
import com.keepit.notify.model.{ EmailRecipient, UserRecipient, Recipient }
import com.keepit.realtime.{ MobilePushNotifier, MessageThreadPushNotification }
import com.keepit.shoebox.ShoeboxServiceClient
import org.joda.time.DateTime
import play.api.libs.json.{ JsObject, Json }
import com.keepit.common.time._

import scala.concurrent.{ ExecutionContext, Future }

class NotificationMessagingCommander @Inject() (
    notificationCommander: NotificationCommander,
    notificationJsonFormat: NotificationJsonFormat,
    notificationInfoGenerator: NotificationInfoGenerator,
    pushNotifier: MobilePushNotifier,
    messageFetchingCommander: MessageFetchingCommander,
    notificationRepo: NotificationRepo,
    messageRepo: MessageRepo,
    messagingCommander: MessagingCommander,
    notificationItemRepo: NotificationItemRepo,
    db: Database,
    webSocketRouter: WebSocketRouter,
    messagingAnalytics: MessagingAnalytics,
    userThreadRepo: UserThreadRepo,
    messageThreadRepo: MessageThreadRepo,
    shoeboxServiceClient: ShoeboxServiceClient,
    implicit val executionContext: ExecutionContext) {

  def combineNotificationsWithThreads(threadJsons: Seq[NotificationJson], notifJsons: Seq[NotificationWithJson], howMany: Option[Int] = None): Seq[JsObject] = {
    val allNotifs = threadJsons.map(Left.apply) ++ notifJsons.map(Right.apply)
    val full = allNotifs.sortBy {
      case Left(old) => (old.obj \ "time").as[DateTime]
      case Right(newn) => newn.notification.lastEvent
    }.map {
      case Left(old) => old.obj
      case Right(newn) => newn.json
    }.reverse
    howMany.fold(full)(full.take)
  }

  def sendUnreadNotifications(recipient: Recipient): Unit = {
    val (unreadMessages, unreadNotifications) = getUnreadNotifications(recipient)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("unread_notifications_count", unreadMessages + unreadNotifications, unreadMessages, unreadNotifications))
      case _ =>
    }
  }

  def getUnreadNotifications(recipient: Recipient): (Int, Int) = recipient match {
    case EmailRecipient(_) => (0, 0)
    case UserRecipient(userId, _) =>
      db.readOnlyMaster { implicit session =>
        (userThreadRepo.getUnreadUnmutedThreadCount(userId),
          notificationRepo.getUnreadNotificationsCount(UserRecipient(userId)))
      }
  }

  def sendUnreadNotificationsWith(notif: Notification, recipient: Recipient): Unit = {
    recipient match {
      case UserRecipient(user, _) =>
        val unreadMessages = messagingCommander.getUnreadUnmutedThreadCount(user)
        val unreadNotifications = notificationCommander.getUnreadNotificationsCount(Recipient(user))
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
    }
  }

  def changeNotificationUnread(userId: Id[User], notif: Notification, item: NotificationItem, unread: Boolean)(implicit context: HeimdalContext): Unit = {
    val updated = notificationCommander.setNotificationUnreadTo(notif.id.get, unread)
    if (updated) {
      val nUrl = ""
      webSocketRouter.sendToUser(userId, Json.arr(if (unread) "message_unread" else "message_read", nUrl, notif.externalId, item.eventTime, item.externalId))
      if (unread) {
        messagingAnalytics.clearedNotification(userId, ExternalId[Message](item.externalId.id), ExternalId[MessageThread](notif.externalId.id), context)
      }
      sendUnreadNotificationsWith(notif, Recipient(userId))
    }
  }

  def setNotificationsUnreadBefore(notif: Notification, recipient: Recipient, item: NotificationItem): Unit = {
    db.readWrite { implicit session =>
      notificationRepo.setAllReadBefore(recipient, item.eventTime)
      recipient match {
        case UserRecipient(id, _) =>
          userThreadRepo.markAllReadAtOrBefore(id, item.eventTime)
        case _ =>
      }
    }
    sendUnreadNotificationsWith(notif, recipient)
    recipient match {
      case UserRecipient(user, _) =>
        webSocketRouter.sendToUser(user, Json.arr("all_notifications_visited", item.externalId, item.eventTime))
      case _ =>
    }
  }

  def getNotificationsWithNewEvents(userId: Id[User], howMany: Int, uriSummary: Boolean = false): Future[NotificationResults] = {
    val recipient = Recipient(userId)
    val newEventNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getNotificationsWithNewEvents(recipient, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(newEventNotifs).flatMap { infos =>
      Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info) })
    }.map { finalNotifs =>
      if (newEventNotifs.length < howMany) {
        FullNotificationResults(finalNotifs)
      } else {
        val (numTotal, numUnread) = db.readOnlyReplica { implicit session =>
          (notificationRepo.getNotificationsWithNewEventsCount(recipient), notificationRepo.getUnreadNotificationsCount(recipient))
        }
        SubsetNotificationResults(finalNotifs, numTotal, numUnread)
      }
    }
  }

  def getNotificationsWithNewEventsBefore(userId: Id[User], time: DateTime, howMany: Int, uriSummary: Boolean = false): Future[Seq[NotificationWithJson]] = {
    val recipient = Recipient(userId)
    val newEventNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getNotificationsWithNewEventsBefore(recipient, time, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(newEventNotifs).flatMap { infos =>
      Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
    }
  }

  def getLatestNotifications(userId: Id[User], howMany: Int, uriSummary: Boolean = false): Future[NotificationResults] = {
    val recipient = Recipient(userId)
    val latestNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getLatestNotifications(recipient, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(latestNotifs).flatMap { infos =>
      val finalNotifsF = Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
      finalNotifsF.map { finalNotifs =>
        if (latestNotifs.length < howMany) {
          FullNotificationResults(finalNotifs)
        } else {
          val (numTotal, numUnread) = db.readOnlyReplica { implicit session =>
            (notificationRepo.getNotificationsWithNewEventsCount(recipient), notificationRepo.getUnreadNotificationsCount(recipient))
          }
          SubsetNotificationResults(finalNotifs, numTotal, numUnread)
        }
      }
    }
  }

  def getLatestNotificationsBefore(userId: Id[User], time: DateTime, howMany: Int, uriSummary: Boolean = false): Future[Seq[NotificationWithJson]] = {
    val recipient = Recipient(userId)
    val latestNotifs = db.readOnlyReplica { implicit session =>
      notificationRepo.getLatestNotificationsBefore(recipient, time, howMany).map { notif =>
        NotificationWithItems(notif, notificationItemRepo.getAllForNotification(notif.id.get).toSet)
      }
    }
    notificationInfoGenerator.generateInfo(latestNotifs).flatMap { infos =>
      Future.sequence(infos.map { info => notificationJsonFormat.extendedJson(info, uriSummary) })
    }
  }

}

object NotificationMessagingCommander {

  class NotificationResults(val results: Seq[NotificationWithJson]) {
    def numTotal = results.count(_.notification.hasNewEvent)
    def numUnread = results.count(_.notification.unread)
  }

  case class FullNotificationResults(override val results: Seq[NotificationWithJson])
    extends NotificationResults(results)

  case class SubsetNotificationResults(override val results: Seq[NotificationWithJson],
    override val numTotal: Int,
    override val numUnread: Int) extends NotificationResults(results)

  case class NotificationResultsForPage(pageUri: String, results: NotificationResults)

}

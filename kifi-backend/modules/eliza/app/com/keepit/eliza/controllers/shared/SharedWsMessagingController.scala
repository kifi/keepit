package com.keepit.eliza.controllers.shared

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.net.HttpClient
import com.keepit.discussion.{ DiscussionFail, Message }
import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders._
import com.keepit.common.db.{ Id, ExternalId, State }
import com.keepit.model._
import com.keepit.common.controller.{ UserActions, UserActionsHelper }
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.model.Recipient
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.RemoteUserExperimentCommander

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.KifiInstallationStore

import scala.concurrent.Future

class SharedWsMessagingController @Inject() (
  discussionCommander: ElizaDiscussionCommander,
  messagingCommander: MessagingCommander,
  basicMessageCommander: MessageFetchingCommander,
  notificationDeliveryCommander: NotificationDeliveryCommander,
  notificationCommander: NotificationCommander,
  notificationMessagingCommander: NotificationMessagingCommander,
  legacyNotificationCheck: LegacyNotificationCheck,
  val userActionsHelper: UserActionsHelper,
  protected val websocketRouter: WebSocketRouter,
  amazonInstanceInfo: AmazonInstanceInfo,
  val kifInstallationStore: KifiInstallationStore,
  protected val shoebox: ShoeboxServiceClient,
  protected val impersonateCookie: ImpersonateCookie,
  protected val actorSystem: ActorSystem,
  protected val clock: Clock,
  protected val airbrake: AirbrakeNotifier,
  protected val heimdal: HeimdalServiceClient,
  protected val heimdalContextBuilder: HeimdalContextBuilderFactory,
  protected val userExperimentCommander: RemoteUserExperimentCommander,
  protected val httpClient: HttpClient,
  val accessLog: AccessLog,
  val shoutdownListener: WebsocketsShutdownListener,
  implicit val publicIdConfig: PublicIdConfiguration)
    extends UserActions with AuthenticatedWebSocketsController {

  protected def onConnect(socket: SocketInfo): Unit = {
    websocketRouter.registerUserSocket(socket)
    sendAnnouncementIfUnseen(socket.userId)
  }

  private def sendAnnouncementIfUnseen(userId: Id[User]): Unit = {
    shoebox.getUserValue(userId, UserValueName.SEEN_ANNOUNCEMENT_NOTIF).map { valueOpt =>
      val hasSeen = valueOpt.contains("true")
      if (!hasSeen) {
        Thread.sleep(5000L)
        notificationCommander.sendAnnouncementToUsers(Set(userId))
        shoebox.setUserValue(userId, UserValueName.SEEN_ANNOUNCEMENT_NOTIF, "true")
      }
    }
  }

  protected def onDisconnect(socket: SocketInfo): Unit = {
    websocketRouter.unregisterUserSocket(socket)
  }

  protected def websocketHandlers(socket: SocketInfo) = Map[String, Seq[JsValue] => Unit](
    "ping" -> { _ =>
      socket.channel.push(Json.arr("pong"))
    },
    "stats" -> { _ => //??
      val stats = Json.obj(
        "connected_for_seconds" -> clock.now.minus(socket.connectedAt.getMillis).getMillis / 1000.0,
        "connected_sockets" -> websocketRouter.connectedSockets,
        "server_ip" -> amazonInstanceInfo.publicIp.toString
      )
      socket.channel.push(Json.arr(s"id:${socket.id}", stats))
    },
    "get_thread" -> {
      case JsString(keepIdStr) +: _ =>
        log.info(s"[get_thread] user ${socket.userId} thread $keepIdStr")
        Keep.decodePublicIdStr(keepIdStr).foreach { keepId =>
          basicMessageCommander.getDiscussionAndKeep(socket.userId, keepId).map {
            case (discussion, keep) =>
              socket.channel.push(Json.arr(
                "thread", Json.obj(
                  "id" -> keepIdStr,
                  "uri" -> discussion.url,
                  "nUrl" -> discussion.nUrl,
                  "participants" -> discussion.participants,
                  "messages" -> discussion.messages.reverse,
                  "keep" -> keep
                )))
          }.recover {
            case fail: DiscussionFail =>
              airbrake.notify(fail)
              socket.channel.push(Json.arr(fail.err, keepIdStr))
            case fail =>
              airbrake.notify(fail)
              socket.channel.push(Json.arr("server_error", keepIdStr))
          }
        }
    },
    "add_participants_to_thread" -> {
      case JsString(keepIdStr) +: (data: JsValue) +: _ =>
        val input = data match {
          case JsArray(elems) => elems
          case _ => (data \ "users").asOpt[Seq[JsValue]].getOrElse(Seq.empty) ++ (data \ "nonUsers").asOpt[Seq[JsValue]].getOrElse(Seq.empty)
        }
        val (users, emailContacts) = messagingCommander.parseRecipients(input)
        if (users.nonEmpty || emailContacts.nonEmpty) {
          implicit val context = authenticatedWebSocketsContextBuilder(socket).build
          Keep.decodePublicIdStr(keepIdStr).foreach { keepId =>
            for {
              userIds <- shoebox.getUserIdsByExternalIds(users.toSet).map(_.values.toList)
            } discussionCommander.editParticipantsOnKeepForOldElizaClients(keepId, socket.userId, userIds, emailContacts, KeepEventSource.fromStr(socket.userAgent))
          }
        }
    },
    "get_unread_notifications_count" -> { _ =>
      val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
      val numUnreadUnmutedNotifications = notificationCommander.getUnreadNotificationsCount(Recipient.fromUser(socket.userId))
      socket.channel.push(Json.arr("unread_notifications_count",
        numUnreadUnmutedMessages + numUnreadUnmutedNotifications,
        numUnreadUnmutedMessages,
        numUnreadUnmutedNotifications))
      // note: "unread_notifications_count" is broadcasted elsewhere too
    },
    // inbox notification/thread handlers
    "get_one_thread" -> {
      case JsNumber(requestId) +: JsString(keepIdStr) +: _ =>
        Keep.decodePublicIdStr(keepIdStr).foreach { keepId =>
          (for {
            jsonOpt <- notificationDeliveryCommander.getSendableNotification(socket.userId, keepId, needsPageImages(socket))
          } yield {
            socket.channel.push(Json.arr(requestId.toLong, jsonOpt.map(_.obj).getOrElse[JsValue](JsNull)))
          }).onFailure {
            case f =>
              airbrake.notify(f)
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_latest_threads" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
        val n = howMany.toInt
        val includeUriSummary = needsPageImages(socket)
        val fut = for {
          threadJsons <- notificationDeliveryCommander.getLatestSendableNotifications(socket.userId, n, includeUriSummary)
          notifJsons <- notificationMessagingCommander.getLatestNotifications(socket.userId, n, includeUriSummary)
        } yield {
          val (unreadThreadCount, unreadUnmutedThreadCount, unreadNotificationCount) = notificationDeliveryCommander.getUnreadCounts(socket.userId)
          val numUnread = unreadThreadCount + unreadNotificationCount
          val numUnreadUnmuted = unreadUnmutedThreadCount + unreadNotificationCount
          (notificationMessagingCommander.combineNotificationsWithThreads(threadJsons, notifJsons.results, Some(n)), numUnread, numUnreadUnmuted)
        }

        fut.foreach {
          case (notices, numTotal, numUnread) =>
            socket.channel.push(Json.arr(requestId.toLong, notices, numTotal, numUnread))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val before = parseStandardTime(time)
        val includeUriSummary = needsPageImages(socket)
        val fut = for {
          threadJsons <- notificationDeliveryCommander.getSendableNotificationsBefore(socket.userId, before, howMany.toInt, includeUriSummary)
          notifJsons <- notificationMessagingCommander.getLatestNotificationsBefore(socket.userId, before, howMany.toInt, includeUriSummary)
        } yield {
          notificationMessagingCommander.combineNotificationsWithThreads(threadJsons, notifJsons, Some(howMany.toInt))
        }

        fut.foreach { notices =>
          socket.channel.push(Json.arr(requestId.toLong, notices))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_unread_threads" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
        val includeUriSummary = needsPageImages(socket)
        val n = howMany.toInt
        val fut = for {
          threadJsons <- notificationDeliveryCommander.getLatestUnreadSendableNotifications(socket.userId, n, includeUriSummary)
          notifJsons <- notificationMessagingCommander.getNotificationsWithNewEvents(socket.userId, n, includeUriSummary)
        } yield {
          val total = threadJsons._2 + notifJsons.numTotal
          (notificationMessagingCommander.combineNotificationsWithThreads(threadJsons._1, notifJsons.results, Some(n)), total)
        }
        fut.foreach {
          case (notices, numTotal) =>
            socket.channel.push(Json.arr(requestId.toLong, notices, numTotal))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_unread_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val before = parseStandardTime(time)
        val includeUriSummary = needsPageImages(socket)
        val fut = for {
          threadJsons <- notificationDeliveryCommander.getUnreadSendableNotificationsBefore(socket.userId, before, howMany.toInt, includeUriSummary)
          notifJsons <- notificationMessagingCommander.getNotificationsWithNewEventsBefore(socket.userId, before, howMany.toInt, includeUriSummary)
        } yield {
          notificationMessagingCommander.combineNotificationsWithThreads(threadJsons, notifJsons, Some(howMany.toInt))
        }

        fut.foreach { notices =>
          socket.channel.push(Json.arr(requestId.toLong, notices))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_sent_threads" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
        val fut = notificationDeliveryCommander.getLatestSentSendableNotifications(socket.userId, howMany.toInt, needsPageImages(socket))
        fut.foreach { notices =>
          socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_sent_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val before = parseStandardTime(time)
        val fut = notificationDeliveryCommander.getSentSendableNotificationsBefore(socket.userId, before, howMany.toInt, needsPageImages(socket))
        fut.foreach { notices =>
          socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_page_threads" -> {
      case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: _ =>
        val fut = notificationDeliveryCommander.getLatestSendableNotificationsForPage(socket.userId, url, howMany.toInt, needsPageImages(socket))
        fut.foreach {
          case (nUriStr, notices, numTotal, numUnread) =>
            socket.channel.push(Json.arr(requestId.toLong, nUriStr, notices.map(_.obj), numTotal, numUnread))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "get_page_threads_before" -> {
      case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val before = parseStandardTime(time)
        val fut = notificationDeliveryCommander.getSendableNotificationsForPageBefore(socket.userId, url, before, howMany.toInt, needsPageImages(socket))
        fut.foreach { notices =>
          socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
        }
        fut.onFailure {
          case f =>
            airbrake.notify(f)
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "set_all_notifications_visited" -> {
      case JsString(notifId) +: _ =>
        legacyNotificationCheck.ifNotifItemExists(notifId) {
          case (notif, item) =>
            val recipient = Recipient.fromUser(socket.userId)
            notificationMessagingCommander.setNotificationsUnreadBefore(notif, recipient, item)
        } {
          Message.decodePublicIdStr(notifId).map(ElizaMessage.fromCommonId).foreach { messageId =>
            val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
            val numUnreadUnmutedNotifications = notificationCommander.getUnreadNotificationsCount(Recipient.fromUser(socket.userId))
            val lastModified = notificationDeliveryCommander.setAllNotificationsReadBefore(socket.userId, messageId, numUnreadUnmutedMessages, numUnreadUnmutedNotifications)
            websocketRouter.sendToUser(socket.userId, Json.arr("all_notifications_visited", notifId, lastModified))
          }
        }
    },

    // end of inbox notification/thread handlers

    "set_message_unread" -> {
      case JsString(messageIdStr) +: opts =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        legacyNotificationCheck.ifNotifItemExists(messageIdStr) {
          case (notif, item) =>
            notificationMessagingCommander.changeNotificationUnread(socket.userId, notif, item, unread = true)
        } {
          Message.decodePublicIdStr(messageIdStr).map(ElizaMessage.fromCommonId).map { messageId =>
            messagingCommander.setUnread(socket.userId, messageId)
          }.getOrElse {
            opts match {
              case JsString(threadId) +: _ =>
                Keep.decodePublicIdStr(threadId).foreach { keepId =>
                  messagingCommander.setUserThreadUnread(socket.userId, keepId)
                }
              case _ =>
            }
          }
        }
    },
    "set_message_read" -> {
      case JsString(messageIdStr) +: opts =>
        val contextBuilder = authenticatedWebSocketsContextBuilder(socket)
        contextBuilder += ("global", false)
        contextBuilder += ("category", NotificationCategory.User.MESSAGE.category) // TODO: Get category from json
        implicit val context = contextBuilder.build
        legacyNotificationCheck.ifNotifItemExists(messageIdStr) {
          case (notif, item) =>
            notificationMessagingCommander.changeNotificationUnread(socket.userId, notif, item, unread = false)
        } {
          Message.decodePublicIdStr(messageIdStr).map(ElizaMessage.fromCommonId).map { messageId =>
            messagingCommander.setRead(socket.userId, messageId)
            messagingCommander.setLastSeen(socket.userId, messageId)
          }.getOrElse {
            opts match {
              case JsString(threadId) +: _ =>
                Keep.decodePublicIdStr(threadId).foreach { keepId =>
                  messagingCommander.setUserThreadRead(socket.userId, keepId)
                  messagingCommander.setLastSeen(socket.userId, keepId)
                }
              case _ =>
            }
          }
        }
    },
    "mute_thread" -> {
      case JsString(idStr) +: _ =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        legacyNotificationCheck.ifNotifExists(idStr) { notif =>
          notificationMessagingCommander.changeNotificationDisabled(socket.userId, notif, disabled = true)
        } {
          Keep.decodePublicIdStr(idStr).foreach { keepId =>
            discussionCommander.muteThread(socket.userId, keepId)
          }
        }
    },
    "unmute_thread" -> {
      case JsString(idStr) +: _ =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        legacyNotificationCheck.ifNotifExists(idStr) { notif =>
          notificationMessagingCommander.changeNotificationDisabled(socket.userId, notif, disabled = false)
        } {
          Keep.decodePublicIdStr(idStr).foreach { keepId =>
            discussionCommander.unmuteThread(socket.userId, keepId)
          }
        }
    },
    "eip" -> {
      case JsString(eip) +: _ =>
        socket.ip = crypt.decrypt(ipkey, eip).toOption.map(_.trim)
    },
    "log_event" -> {
      case JsObject(pairs) +: _ =>
        implicit val experimentFormat = State.format[UserExperimentType]
        val eventJson = JsObject(pairs).deepMerge(
          Json.obj("experiments" -> socket.experiments)
        )
        //TEMPORARY STOP GAP
        val eventName = (eventJson \ "eventName").as[String]
        if (eventName == "sliderShown") shoebox.logEvent(socket.userId, eventJson)
      //else discard!
    }
  )

  private def needsPageImages(socket: SocketInfo): Boolean = {
    socket.kifiVersion match {
      case Some(ver: KifiExtVersion) => ver >= KifiExtVersion(3, 2)
      case _ => false
    }
  }
}

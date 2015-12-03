package com.keepit.eliza.controllers.shared

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.net.HttpClient
import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders._
import com.keepit.common.db.{ ExternalId, State }
import com.keepit.model.{ Library, NotificationCategory, UserExperimentType, KifiExtVersion }
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
import com.keepit.commanders.{ ProcessedImageSize, RemoteUserExperimentCommander }

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.common.logging.AccessLog
import com.keepit.common.store.KifiInstallationStore

import scala.concurrent.Future

class SharedWsMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  basicMessageCommander: MessageFetchingCommander,
  notificationDeliveryCommander: NotificationDeliveryCommander,
  notificationCommander: NotificationCommander,
  notificationMessagingCommander: NotificationMessagingCommander,
  legacyNotificationCheck: LegacyNotificationCheck,
  val userActionsHelper: UserActionsHelper,
  protected val websocketRouter: WebSocketRouter,
  private implicit val publicIdConfig: PublicIdConfiguration,
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
  val shoutdownListener: WebsocketsShutdownListener)
    extends UserActions with AuthenticatedWebSocketsController {

  protected def onConnect(socket: SocketInfo): Unit = {
    websocketRouter.registerUserSocket(socket)
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
      case JsString(threadId) +: _ =>
        log.info(s"[get_thread] user ${socket.userId} thread $threadId")
        for {
          (thread, msgs) <- basicMessageCommander.getThreadMessagesWithBasicUser(socket.userId, ExternalId[MessageThread](threadId))
          keepOpt <- thread.keepId.map(kid => shoebox.getBasicKeepsByIds(Set(kid)).map(res => res.values.headOption)).getOrElse(Future.successful(None))
          libOpt <- keepOpt.flatMap(k => Library.decodePublicId(k.libraryId).toOption).map { libId =>
            shoebox.getBasicLibraryDetails(Set(libId), idealImageSize = ProcessedImageSize.Small.idealSize, viewerId = Some(socket.userId)).map(res => res.values.headOption)
          }.getOrElse(Future.successful(None))
        } {
          SafeFuture(socket.channel.push(Json.arr(
            "thread", Json.obj(
              "id" -> threadId,
              "uri" -> thread.url,
              "keep" -> keepOpt,
              "library" -> libOpt,
              "messages" -> msgs.reverse
            ))))
        }
    },
    "add_participants_to_thread" -> {
      case JsString(threadId) +: (data: JsValue) +: _ =>
        val (users, emailContacts, orgs) = data match {
          case JsArray(participantsJson) =>
            val (users, emailContacts, orgs) = messagingCommander.parseRecipients(participantsJson)
            (users, emailContacts, orgs)
          case _ =>
            val (users, _, _) = messagingCommander.parseRecipients((data \ "users").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            val (_, _, orgs) = messagingCommander.parseRecipients((data \ "users").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            val (_, contacts, _) = messagingCommander.parseRecipients((data \ "nonUsers").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            (users, contacts, orgs)
        }

        if (users.nonEmpty || emailContacts.nonEmpty || orgs.nonEmpty) {
          implicit val context = authenticatedWebSocketsContextBuilder(socket).build
          messagingCommander.addParticipantsToThread(socket.userId, ExternalId[MessageThread](threadId), users, emailContacts, orgs)
        }
    },
    "get_unread_notifications_count" -> { _ =>
      val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
      val numUnreadUnmutedNotifications = notificationCommander.getUnreadNotificationsCount(Recipient(socket.userId))
      socket.channel.push(Json.arr("unread_notifications_count",
        numUnreadUnmutedMessages + numUnreadUnmutedNotifications,
        numUnreadUnmutedMessages,
        numUnreadUnmutedNotifications))
      // note: "unread_notifications_count" is broadcasted elsewhere too
    },
    // inbox notification/thread handlers
    "get_one_thread" -> {
      case JsNumber(requestId) +: JsString(threadId) +: _ =>
        val fut = notificationDeliveryCommander.getSendableNotification(socket.userId, ExternalId[MessageThread](threadId), needsPageImages(socket))
        fut.foreach { json =>
          socket.channel.push(Json.arr(requestId.toLong, json.obj))
        }
        fut.onFailure {
          case _ =>
            socket.channel.push(Json.arr("server_error", requestId.toLong))
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
          case e =>
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
          case _ =>
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
          case _ =>
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
          case _ =>
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
          case _ =>
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
          case _ =>
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
          case _ =>
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
          case _ =>
            socket.channel.push(Json.arr("server_error", requestId.toLong))
        }
    },
    "set_all_notifications_visited" -> {
      case JsString(notifId) +: _ =>
        val messageId = ExternalId[ElizaMessage](notifId)
        legacyNotificationCheck.ifNotifItemExists(notifId) {
          case (notif, item) =>
            val recipient = Recipient(socket.userId)
            notificationMessagingCommander.setNotificationsUnreadBefore(notif, recipient, item)
        } {
          val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
          val numUnreadUnmutedNotifications = notificationCommander.getUnreadNotificationsCount(Recipient(socket.userId))
          val lastModified = notificationDeliveryCommander.setAllNotificationsReadBefore(socket.userId, messageId, numUnreadUnmutedMessages, numUnreadUnmutedNotifications)
          websocketRouter.sendToUser(socket.userId, Json.arr("all_notifications_visited", notifId, lastModified))
        }
    },

    // end of inbox notification/thread handlers

    "set_message_unread" -> {
      case JsString(messageId) +: _ =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        legacyNotificationCheck.ifNotifItemExists(messageId) {
          case (notif, item) =>
            notificationMessagingCommander.changeNotificationUnread(socket.userId, notif, item, unread = true)
        } {
          messagingCommander.setUnread(socket.userId, ExternalId[ElizaMessage](messageId))
        }
    },
    "set_message_read" -> {
      case JsString(messageId) +: _ =>
        val msgExtId = ExternalId[ElizaMessage](messageId)
        val contextBuilder = authenticatedWebSocketsContextBuilder(socket)
        contextBuilder += ("global", false)
        contextBuilder += ("category", NotificationCategory.User.MESSAGE.category) // TODO: Get category from json
        implicit val context = contextBuilder.build
        legacyNotificationCheck.ifNotifItemExists(messageId) {
          case (notif, item) =>
            notificationMessagingCommander.changeNotificationUnread(socket.userId, notif, item, unread = false)
        } {
          messagingCommander.setRead(socket.userId, msgExtId)
          messagingCommander.setLastSeen(socket.userId, msgExtId)
        }
    },
    "mute_thread" -> {
      case JsString(jsThreadId) +: _ =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        legacyNotificationCheck.ifNotifExists(jsThreadId) { notif =>
          notificationMessagingCommander.changeNotificationDisabled(socket.userId, notif, disabled = true)
        } {
          messagingCommander.muteThread(socket.userId, ExternalId[MessageThread](jsThreadId))
        }
    },
    "unmute_thread" -> {
      case JsString(jsThreadId) +: _ =>
        implicit val context = authenticatedWebSocketsContextBuilder(socket).build
        val notifExternalId = ExternalId[Notification](jsThreadId)
        legacyNotificationCheck.ifNotifExists(jsThreadId) { notif =>
          notificationMessagingCommander.changeNotificationDisabled(socket.userId, notif, disabled = false)
        } {
          messagingCommander.unmuteThread(socket.userId, ExternalId[MessageThread](jsThreadId))
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

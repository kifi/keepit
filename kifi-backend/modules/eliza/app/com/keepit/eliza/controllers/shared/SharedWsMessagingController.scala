package com.keepit.eliza.controllers.shared

import com.keepit.eliza.commanders.NotificationMessagingCommander.NotificationResultsForPage
import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders._
import com.keepit.common.db.{ ExternalId, State }
import com.keepit.model.{ User, NotificationCategory, UserExperimentType, KifiExtVersion }
import com.keepit.common.controller.{ UserActions, UserActionsHelper }
import com.keepit.notify.LegacyNotificationCheck
import com.keepit.notify.model.Recipient
import com.keepit.notify.model.event.NewMessage
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
import scala.util.{ Success, Failure }

class SharedWsMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  basicMessageCommander: MessageFetchingCommander,
  notificationDeliveryCommander: NotificationDeliveryCommander,
  notificationMessagingCommander: NotificationMessagingCommander,
  legacyNotificationCheck: LegacyNotificationCheck,
  val userActionsHelper: UserActionsHelper,
  protected val websocketRouter: WebSocketRouter,
  amazonInstanceInfo: AmazonInstanceInfo,
  threadRepo: MessageThreadRepo,
  val kifInstallationStore: KifiInstallationStore,
  protected val shoebox: ShoeboxServiceClient,
  protected val impersonateCookie: ImpersonateCookie,
  protected val actorSystem: ActorSystem,
  protected val clock: Clock,
  protected val airbrake: AirbrakeNotifier,
  protected val heimdal: HeimdalServiceClient,
  protected val heimdalContextBuilder: HeimdalContextBuilderFactory,
  protected val userExperimentCommander: RemoteUserExperimentCommander,
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
        legacyNotificationCheck.ifNotifExists(threadId) { notif =>
          notificationMessagingCommander.getNotificationMessages(socket.userId, notif.id.get) map {
            case (threadObj) =>
              SafeFuture(socket.channel.push(Json.arr("thread", threadObj)))
          } onFailure {
            case e =>
              e.printStackTrace
              println(e)
          }
        } {
          basicMessageCommander.getThreadMessagesWithBasicUser(socket.userId, ExternalId[MessageThread](threadId)) map {
            case (thread, msgs) =>
              val url = thread.url.getOrElse("") // needs to change when we have detached threads
              SafeFuture(socket.channel.push(Json.arr("thread", Json.obj("id" -> threadId, "uri" -> url, "messages" -> msgs.reverse))))
          }
        }
    },
    "add_participants_to_thread" -> {
      case JsString(threadId) +: (data: JsValue) +: _ =>
        val (users, emailContacts, orgs) = data match {
          case JsArray(whoDat) => {
            val (users, emailContacts, orgs) = messagingCommander.parseRecipients(whoDat)
            (users, emailContacts, orgs)
          }
          case _ => {
            val (users, _, _) = messagingCommander.parseRecipients((data \ "users").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            val (_, _, orgs) = messagingCommander.parseRecipients((data \ "users").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            val (_, contacts, _) = messagingCommander.parseRecipients((data \ "nonUsers").asOpt[Seq[JsValue]].getOrElse(Seq.empty))
            (users, contacts, orgs)
          }
        }

        if (users.nonEmpty || emailContacts.nonEmpty) {
          implicit val context = authenticatedWebSocketsContextBuilder(socket).build
          messagingCommander.addParticipantsToThread(socket.userId, ExternalId[MessageThread](threadId), users, emailContacts, orgs)
        }
    },
    "get_unread_notifications_count" -> { _ =>
      val recipient = Recipient(socket.userId)
      legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
        notificationMessagingCommander.sendUnreadNotifications(recipient)
      } { recip =>
        val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId, Some(true))
        val numUnreadUnmutedNotifications = messagingCommander.getUnreadUnmutedThreadCount(socket.userId, Some(false))
        socket.channel.push(Json.arr("unread_notifications_count", numUnreadUnmutedMessages + numUnreadUnmutedNotifications, numUnreadUnmutedMessages, numUnreadUnmutedNotifications))
      }
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
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getLatestNotifications(socket.userId, howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.results.map(_.json), results.numTotal, results.numUnread))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getLatestSendableNotifications(socket.userId, howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            val (numUnread, numUnreadUnmuted) = messagingCommander.getUnreadThreadCounts(socket.userId)
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj), numUnreadUnmuted, numUnread, currentDateTime))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getLatestNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_unread_threads" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getNotificationsWithNewEvents(socket.userId, howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getLatestUnreadSendableNotifications(socket.userId, howMany.toInt, needsPageImages(socket))
          fut.foreach {
            case (notices, numTotal) =>
              socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj), numTotal))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_unread_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getNotificationsWithNewEventsBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getUnreadSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_sent_threads" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getNotificationsForSentMessages(socket.userId, howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getLatestSentSendableNotifications(socket.userId, howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_sent_threads_before" -> {
      case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recipient =>
          notificationMessagingCommander.getNotificationsForSentMessagesBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getSentSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_page_threads" -> {
      case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recip =>
          notificationMessagingCommander.getNotificationsForPage(socket.userId, url, howMany.toInt, needsPageImages(socket)).andThen {
            case Success(NotificationResultsForPage(page, results)) =>
              socket.channel.push(Json.arr(requestId.toLong, page, results.results.map(_.json), results.numTotal, results.numUnread))
            case Failure(e) =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getLatestSendableNotificationsForPage(socket.userId, url, howMany.toInt, needsPageImages(socket))
          fut.foreach {
            case (nUriStr, notices, numTotal, numUnread) =>
              socket.channel.push(Json.arr(requestId.toLong, nUriStr, notices.map(_.obj), numTotal, numUnread))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "get_page_threads_before" -> {
      case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: JsString(time) +: _ =>
        val recipient = Recipient(socket.userId)
        legacyNotificationCheck.ifElseUserExperiment(recipient) { recip =>
          notificationMessagingCommander.getNotificationsForPageBefore(socket.userId, url, parseStandardTime(time), howMany.toInt, needsPageImages(socket)).andThen {
            case Success(results) =>
              socket.channel.push(Json.arr(requestId.toLong, results.map(_.json)))
            case Failure(_) => socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        } { recip =>
          val fut = notificationDeliveryCommander.getSendableNotificationsForPageBefore(socket.userId, url, parseStandardTime(time), howMany.toInt, needsPageImages(socket))
          fut.foreach { notices =>
            socket.channel.push(Json.arr(requestId.toLong, notices.map(_.obj)))
          }
          fut.onFailure {
            case _ =>
              socket.channel.push(Json.arr("server_error", requestId.toLong))
          }
        }
    },
    "set_all_notifications_visited" -> {
      case JsString(notifId) +: _ =>
        val messageId = ExternalId[Message](notifId)
        legacyNotificationCheck.ifNotifItemExists(notifId) {
          case (notif, item) =>
            val recipient = Recipient(socket.userId)
            notificationMessagingCommander.setNotificationsUnreadBefore(notif, recipient, item)
        } {
          val numUnreadUnmutedMessages = messagingCommander.getUnreadUnmutedThreadCount(socket.userId, Some(true))
          val numUnreadUnmutedNotifications = messagingCommander.getUnreadUnmutedThreadCount(socket.userId, Some(false))
          val lastModified = notificationDeliveryCommander.setAllNotificationsReadBefore(socket.userId, messageId, numUnreadUnmutedMessages, numUnreadUnmutedNotifications)
          socket.channel.push(Json.arr("all_notifications_visited", notifId, lastModified))
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
          messagingCommander.setUnread(socket.userId, ExternalId[Message](messageId))
        }
    },
    "set_message_read" -> {
      case JsString(messageId) +: _ =>
        val msgExtId = ExternalId[Message](messageId)
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

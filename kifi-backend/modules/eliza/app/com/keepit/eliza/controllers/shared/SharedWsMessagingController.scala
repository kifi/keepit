package com.keepit.eliza.controllers.shared

import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders.{NotificationCommander, MessagingCommander}
import com.keepit.common.db.{ExternalId, State}
import com.keepit.model.{NotificationCategory, User, ExperimentType}
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.commanders.RemoteUserExperimentCommander


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{Json, JsValue, JsObject, JsArray, JsString, JsNumber}

import akka.actor.ActorSystem

import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.common.store.KifInstallationStore
import com.keepit.common.logging.AccessLog
import com.keepit.common.shutdown.ShutdownCommander

class SharedWsMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    notificationCommander: NotificationCommander,
    actionAuthenticator: ActionAuthenticator,
    protected val websocketRouter: WebSocketRouter,
    amazonInstanceInfo: AmazonInstanceInfo,
    threadRepo: MessageThreadRepo,
    protected val shoebox: ShoeboxServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem,
    protected val clock: Clock,
    protected val airbrake: AirbrakeNotifier,
    protected val heimdal: HeimdalServiceClient,
    protected val heimdalContextBuilder: HeimdalContextBuilderFactory,
    protected val userExperimentCommander: RemoteUserExperimentCommander,
    val kifInstallationStore: KifInstallationStore,
    val accessLog: AccessLog,
    val shoutdownListener: WebsocketsShutdownListener
  )
  extends BrowserExtensionController(actionAuthenticator) with AuthenticatedWebSocketsController {

  protected def onConnect(socket: SocketInfo) : Unit = {
    websocketRouter.registerUserSocket(socket)
  }

  protected def onDisconnect(socket: SocketInfo) : Unit = {
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
    "get_thread" -> { case JsString(threadId) +: _ =>
      log.info(s"[get_thread] user ${socket.userId} thread $threadId")
      messagingCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None) map { case (thread, msgs) =>
        val url = thread.url.getOrElse("")  // needs to change when we have detached threads
        val msgsWithModifiedAuxData = msgs.map { m =>
          messagingCommander.modifyMessageWithAuxData(m)
        }
        SafeFuture(Future.sequence(msgsWithModifiedAuxData).map { completeMsgs =>
          socket.channel.push(Json.arr("thread", Json.obj("id" -> threadId, "uri" -> url, "messages" -> completeMsgs.reverse)))
        })
      }
    },
    "add_participants_to_thread" -> { case JsString(threadId) +: JsArray(extUserIds) +: _ =>
      val users = extUserIds.map { case s =>
        ExternalId[User](s.asInstanceOf[JsString].value)
      }
      implicit val context = authenticatedWebSocketsContextBuilder(socket).build
      messagingCommander.addParticipantsToThread(socket.userId, ExternalId[MessageThread](threadId), users)
    },
    "get_unread_notifications_count" -> { _ =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
      socket.channel.push(Json.arr("unread_notifications_count", numUnreadUnmuted))
      // note: "unread_notifications_count" is broadcasted elsewhere too
    },
    // inbox notification/thread handlers
    "get_one_thread" -> { case JsNumber(requestId) +: JsString(threadId) +: _ =>
      val fut = notificationCommander.getSendableNotification(socket.userId, ExternalId[MessageThread](threadId))
      fut.foreach { json =>
        socket.channel.push(Json.arr(requestId.toLong, json))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_latest_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      val fut = notificationCommander.getLatestSendableNotifications(socket.userId, howMany.toInt)
      fut.foreach { notices =>
        val (numUnread, numUnreadUnmuted) = messagingCommander.getUnreadThreadCounts(socket.userId)
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons, numUnreadUnmuted, numUnread, currentDateTime))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      val fut = notificationCommander.getSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt)
      fut.foreach { notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_unread_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      val fut = notificationCommander.getLatestUnreadSendableNotifications(socket.userId, howMany.toInt)
      fut.foreach { case (notices, numTotal) =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons, numTotal))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_unread_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      val fut = notificationCommander.getUnreadSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt)
      fut.foreach { notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_sent_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      val fut = notificationCommander.getLatestSentSendableNotifications(socket.userId, howMany.toInt)
      fut.foreach { notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_sent_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      val fut = notificationCommander.getSentSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt)
      fut.foreach { notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_page_threads" -> { case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: _ =>
      val fut = notificationCommander.getLatestSendableNotificationsForPage(socket.userId, url, howMany.toInt)
      fut.foreach { case (nUriStr, notices, numTotal, numUnreadUnmuted) =>
        socket.channel.push(Json.arr(requestId.toLong, nUriStr, notices.jsons, numTotal, numUnreadUnmuted))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },
    "get_page_threads_before" -> { case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: JsString(time) +: _ =>
      val fut = notificationCommander.getSendableNotificationsForPageBefore(socket.userId, url, parseStandardTime(time), howMany.toInt)
      fut.foreach { notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices.jsons))
      }
      fut.onFailure { case _ =>
        socket.channel.push(Json.arr("server_error", requestId.toLong))
      }
    },

    // end of inbox notification/thread handlers

    "set_message_unread" -> { case JsString(messageId) +: _ =>
      messagingCommander.setUnread(socket.userId, ExternalId[Message](messageId))
    },
    "set_message_read" -> { case JsString(messageId) +: _ =>
      val msgExtId = ExternalId[Message](messageId)
      val contextBuilder = authenticatedWebSocketsContextBuilder(socket)
      contextBuilder += ("global", false)
      contextBuilder += ("category", NotificationCategory.User.MESSAGE.category) // TODO: Get category from json
      implicit val context = contextBuilder.build
      messagingCommander.setRead(socket.userId, msgExtId)
      messagingCommander.setLastSeen(socket.userId, msgExtId)
    },
    "mute_thread" -> { case JsString(jsThreadId) +: _ =>
      implicit val context = authenticatedWebSocketsContextBuilder(socket).build
      messagingCommander.muteThread(socket.userId, ExternalId[MessageThread](jsThreadId))
    },
    "unmute_thread" -> { case JsString(jsThreadId) +: _ =>
      implicit val context = authenticatedWebSocketsContextBuilder(socket).build
      messagingCommander.unmuteThread(socket.userId, ExternalId[MessageThread](jsThreadId))
    },
    "eip" -> { case JsString(eip) +: _ =>
      socket.ip = crypt.decrypt(ipkey, eip).toOption
    },
    "log_event" -> { case JsObject(pairs) +: _ =>
      implicit val experimentFormat = State.format[ExperimentType]
      val eventJson = JsObject(pairs).deepMerge(
        Json.obj("experiments" -> socket.experiments)
      )
      //TEMPORARY STOP GAP
      val eventName = (eventJson \ "eventName").as[String]
      if (eventName == "sliderShown") shoebox.logEvent(socket.userId, eventJson)
      //else discard!
    }
  )
}

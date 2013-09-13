package com.keepit.eliza

import com.keepit.common.db.{ExternalId, State}
import com.keepit.model.{User, ExperimentType}
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.{HealthcheckPlugin}

import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.iteratee.Concurrent
import play.api.libs.json.{Json, JsValue, JsArray, JsString, JsNumber, JsNull, JsObject}
import play.modules.statsd.api.Statsd

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.common.db.slick.Database


class ExtMessagingController @Inject() (
    messagingController: MessagingController,
    actionAuthenticator: ActionAuthenticator,
    notificationRouter: NotificationRouter,
    amazonInstanceInfo: AmazonInstanceInfo,
    threadRepo: MessageThreadRepo,
    db: Database,
    protected val shoebox: ShoeboxServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem,
    protected val clock: Clock,
    protected val healthcheckPlugin: HealthcheckPlugin
  )
  extends BrowserExtensionController(actionAuthenticator) with AuthenticatedWebSocketsController {

  /*********** REST *********************/

  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val tStart = currentDateTime
    val o = request.body
    val (title, text, recipients) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim,
      (o \ "recipients").as[Seq[String]])
    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val responseFuture = messagingController.constructRecipientSet(recipients.map(ExternalId[User](_))).map{ recipientSet =>
      val message : Message = messagingController.sendNewMessage(request.user.id.get, recipientSet, urls, title, text)
      val tDiff = currentDateTime.getMillis - tStart.getMillis
      Statsd.timing(s"messaging.newMessage", tDiff)
      Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
    }
    Async(responseFuture)
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = AuthenticatedJsonToJsonAction { request =>
    val tStart = currentDateTime
    val o = request.body
    val text = (o \ "text").as[String].trim

    val message = messagingController.sendMessage(request.user.id.get, threadExtId, text, None)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    Statsd.timing(s"messaging.replyMessage", tDiff)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }

  def getChatter() = AuthenticatedJsonToJsonAction { request =>
    val urls = request.body.as[Seq[String]]
    Async {
      messagingController.getChatter(request.user.id.get, urls).map { res =>
        val built = res.map { case (url, msgs) =>
          val threadId = if (msgs.size == 1) {
            db.readOnly { implicit session =>
              Some(threadRepo.get(msgs.head).externalId)
            }
          } else None
          url -> JsObject(
            Seq("threads" -> JsNumber(msgs.size)) ++
            (if (threadId.isDefined) Seq("threadId" -> JsString(threadId.get.id)) else Nil))
        }.toSeq
        Ok(JsObject(built))
      }
    }
  }

  /*********** WEBSOCKETS ******************/

  protected def onConnect(socket: SocketInfo) : Unit = {
    notificationRouter.registerUserSocket(socket)
  }

  protected def onDisconnect(socket: SocketInfo) : Unit = {
    notificationRouter.unregisterUserSocket(socket)
  }

  protected def websocketHandlers(socket: SocketInfo) = Map[String, Seq[JsValue] => Unit](
    "ping" -> { _ =>
      log.info(s"Received ping from user ${socket.userId} on socket ${socket.id}")
      socket.channel.push(Json.arr("pong"))
      log.info(s"Sent pong to user ${socket.userId} on socket ${socket.id}")
    },
    "stats" -> { _ =>
      val stats = Json.obj(
        "connected_for_seconds" -> clock.now.minus(socket.connectedAt.getMillis).getMillis / 1000.0,
        "connected_sockets" -> notificationRouter.connectedSockets,
        "server_ip" -> amazonInstanceInfo.publicIp.toString
      )
      socket.channel.push(Json.arr(s"id:${socket.id}", stats))
    },
    "get_thread" -> { case JsString(threadId) +: _ =>
      log.info(s"[get_thread] user ${socket.userId} requesting thread extId $threadId")
        messagingController.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None) map { messages =>
        log.info(s"[get_thread] got messages: $messages")
        val url = messages.headOption.map(_.nUrl).getOrElse("") //TODO: this needs to change when we have detached threads!
        socket.channel.push(
          Json.arr("thread",
            Json.obj("id" -> threadId, "uri" -> url, "messages" -> messages.reverse)
          )
        )
      }
    },
    "set_all_notifications_visited" -> { case JsString(notifId) +: _ =>
      val messageId = ExternalId[Message](notifId)
      val lastModified = messagingController.setAllNotificationsReadBefore(socket.userId, messageId)
      socket.channel.push(Json.arr("all_notifications_visited", notifId, lastModified))
    },
    "get_last_notify_read_time" -> { _ =>
      val tOpt = messagingController.getNotificationLastSeen(socket.userId)
      tOpt.map { t =>
        socket.channel.push(Json.arr("last_notify_read_time", t.toStandardTimeString))
      }
    },
    "set_last_notify_read_time" -> { case JsString(time) +: _ =>
      val t = parseStandardTime(time)
      messagingController.setNotificationLastSeen(socket.userId, t)
      socket.channel.push(Json.arr("last_notify_read_time", t.toStandardTimeString))
    },
    "get_notifications" -> { case JsNumber(howMany) +: _ =>
      val notices = messagingController.getLatestSendableNotifications(socket.userId, howMany.toInt)
      val unvisited = messagingController.getPendingNotificationCount(socket.userId)
      socket.channel.push(Json.arr("notifications", notices, unvisited))
    },
    "get_missed_notifications" -> { case JsString(time) +: _ =>
      val notices = messagingController.getSendableNotificationsAfter(socket.userId, parseStandardTime(time))
      socket.channel.push(Json.arr("missed_notifications", notices, currentDateTime))
    },
    "get_old_notifications" -> { case JsNumber(requestId) +: JsString(time) +: JsNumber(howMany) +: _ =>
      val notices = messagingController.getSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt)
      socket.channel.push(Json.arr(requestId.toLong, notices))
    },
    "get_unread_notifications_count" -> { _ =>
      val unvisited = messagingController.getPendingNotificationCount(socket.userId)
      socket.channel.push(Json.arr("unread_notifications_count", unvisited))
    },
    "set_message_read" -> { case JsString(messageId) +: _ =>
      val msgExtId = ExternalId[Message](messageId)
      messagingController.setNotificationReadForMessage(socket.userId, msgExtId)
      messagingController.setLastSeen(socket.userId, msgExtId)
    },
    "set_global_read" -> { case JsString(messageId) +: _ =>
      messagingController.setNotificationReadForMessage(socket.userId, ExternalId[Message](messageId))
      messagingController.setLastSeen(socket.userId, ExternalId[Message](messageId))
    },
    "get_threads_by_url" -> { case JsString(url) +: _ =>
      val threadInfos = messagingController.getThreadInfos(socket.userId, url)
      socket.channel.push(Json.arr("thread_infos", threadInfos))
    },
    "log_event" -> { case JsObject(pairs) +: _ =>
      implicit val experimentFormat = State.format[ExperimentType]
      val eventJson = JsObject(pairs).deepMerge(
        Json.obj("experiments" -> socket.experiments)
      )
      shoebox.logEvent(socket.userId, eventJson)
    }
  )
}

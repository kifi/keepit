package com.keepit.eliza

import com.keepit.common.db.ExternalId
import com.keepit.model.User
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._

import scala.concurrent.ExecutionContext.Implicits.global

import play.api.libs.iteratee.Concurrent
import play.api.libs.json.{Json, JsValue, JsArray, JsString, JsNumber}

import akka.actor.ActorSystem

import com.google.inject.Inject



class ExtMessagingController @Inject() (
    messagingController: MessagingController,
    actionAuthenticator: ActionAuthenticator,
    notificationRouter: NotificationRouter,
    protected val shoebox: ShoeboxServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem,
    protected val clock: Clock
  ) 
  extends BrowserExtensionController(actionAuthenticator) with AuthenticatedWebSocketsController {


  /*********** REST *********************/

  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val (urlStr, title, text, recipients) = (
      (o \ "url").as[String],
      (o \ "title").as[String],
      (o \ "text").as[String].trim,
      (o \ "recipients").as[Seq[String]])


    val responseFuture = messagingController.constructRecipientSet(recipients.map(ExternalId[User](_))).map{ recipientSet =>
      val message : Message = messagingController.sendNewMessage(request.user.id.get, recipientSet, Some(urlStr), text)
      Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))  
    }
    Async(responseFuture)
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = AuthenticatedJsonToJsonAction { request =>
    val o = request.body
    val text = (o \ "text").as[String].trim

    val message = messagingController.sendMessage(request.user.id.get, threadExtId, text, None)

    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
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
      socket.channel.push(Json.arr(s"id:${socket.id}", clock.now.minus(socket.connectedAt.getMillis).getMillis / 1000.0))
    },
    "get_thread" -> { case JsString(threadId) +: _ =>
      val messages = messagingController.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None)
      val url = messages.headOption.map(_.nUrl).getOrElse("") //TODO: this needs to change when we have detached threads!
      socket.channel.push(
        Json.arr("thread", 
          Json.obj("id" -> threadId, "uri" -> url, "messages" -> messages)
        )
      )
    },
    "set_all_notifications_visited" -> { case JsString(notifId) +: _ =>
      val messageId = ExternalId[Message](notifId)
      val lastModified = messagingController.setAllNotificationsReadBefore(socket.userId, messageId)
      socket.channel.push(Json.arr("all_notifications_visited", notifId, lastModified))
    },
    "get_last_notify_read_time" -> { _ =>
      val t = messagingController.getNotificationLastSeen(socket.userId)
      socket.channel.push(Json.arr("last_notify_read_time", t.map(_.toStandardTimeString)))
    },
    "set_last_notify_read_time" -> { case JsString(time) +: _ =>
      val t = parseStandardTime(time)
      messagingController.setNotificationLastSeen(socket.userId, t)
      socket.channel.push(Json.arr("last_notify_read_time", t.toStandardTimeString))
    },
    "get_notifications" -> { case JsNumber(howMany) +: _ =>
      socket.channel.push(Json.arr("hey_andrew"))
    },
    "asdf" -> { case JsNumber(howMany) +: _ =>
      val notices = messagingController.getLatestSendableNotifications(socket.userId, howMany.toInt)
      val unvisited = messagingController.getPendingNotificationCount(socket.userId)
      socket.channel.push(Json.arr("notifications", notices, unvisited))
    },
    "asdf2" -> { case JsNumber(howMany) +: _ =>
      val notices = messagingController.getLatestSendableNotifications(socket.userId, howMany.toInt)
      val unvisited = messagingController.getPendingNotificationCount(socket.userId)
      socket.channel.push(Json.arr("Hey There!"))
    },
    "get_missed_notifications" -> { case JsString(time) +: _ =>
      val notices = messagingController.getSendableNotificationsAfter(socket.userId, parseStandardTime(time))
      socket.channel.push(Json.arr("missed_notifications", notices))
    },
    "get_old_notifications" -> { case JsNumber(requestId) +: JsString(time) +: JsNumber(howMany) +: _ =>
      val notices = messagingController.getSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt)
      socket.channel.push(Json.arr(requestId.toLong, notices))
    },
    "set_message_read" -> { case JsString(messageId) +: _ =>
      messagingController.setLastSeen(socket.userId, ExternalId[Message](messageId))
    },
    "set_global_read" -> { case JsString(messageId) +: _ =>
      messagingController.setLastSeen(socket.userId, ExternalId[Message](messageId))
    },
    "get_threads_by_url" -> { case JsString(url) +: _ =>
      val threadInfos = messagingController.getThreadInfos(socket.userId, url)
      socket.channel.push(Json.arr("thread_infos", threadInfos))
    }
    // TODO Stephen: Send this on to shoebox
    // "log_event" -> { case JsObject(pairs) +: _ =>
    //   logEvent(streamSession, JsObject(pairs))
    // },
  )




}




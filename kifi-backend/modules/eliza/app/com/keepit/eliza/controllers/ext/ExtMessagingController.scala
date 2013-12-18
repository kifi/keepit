package com.keepit.eliza.controllers.ext

import com.keepit.eliza._
import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.controllers.internal.MessagingController
import com.keepit.common.db.{ExternalId, State}
import com.keepit.model.{User, ExperimentType}
import com.keepit.common.controller.{BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.{ShoeboxServiceClient}
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.common.akka.SafeFuture
import com.keepit.common.db.Id
import com.keepit.search.SearchServiceClient
import com.keepit.common.crypto.SimpleDESCrypt

import scala.util.{Success, Failure}

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.iteratee.Concurrent
import play.api.libs.json.{Json, JsValue, JsObject, JsArray, JsString, JsNumber}
import play.modules.statsd.api.Statsd

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.social.BasicUser
import scala.concurrent.{Future, Promise}
import com.keepit.eliza.model.{NonUserEmailParticipant, NonUserParticipant}
import com.keepit.common.mail.GenericEmailAddress

class ExtMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    actionAuthenticator: ActionAuthenticator,
    notificationRouter: NotificationRouter,
    amazonInstanceInfo: AmazonInstanceInfo,
    threadRepo: MessageThreadRepo,
    protected val shoebox: ShoeboxServiceClient,
    protected val search: SearchServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem,
    protected val clock: Clock,
    protected val airbrake: AirbrakeNotifier,
    protected val heimdal: HeimdalServiceClient,
    protected val heimdalContextBuilder: HeimdalContextBuilderFactory
  )
  extends BrowserExtensionController(actionAuthenticator) with AuthenticatedWebSocketsController {

  private val crypt = new SimpleDESCrypt
  private val ipkey = crypt.stringToKey("dontshowtheiptotheclient")

  /*********** REST *********************/

  def sendMessageAction() = AuthenticatedJsonToJsonAction { request =>
    val tStart = currentDateTime
    val o = request.body

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    (o \ "extVersion").asOpt[String].foreach { version => contextBuilder += ("extensionVersion", version) }

    val (title, text) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim
    )
    val (userExtRecipients, nonUserRecipients) = recipientJsonToTypedFormat((o \ "recipients").as[Seq[JsValue]])

    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val messageSubmitResponse = for {
      userRecipients <- messagingCommander.constructUserRecipients(userExtRecipients)
      nonUserRecipients <- messagingCommander.constructNonUserRecipients(request.userId, nonUserRecipients)
    } yield {
      val (thread, message) = messagingCommander.sendNewMessage(request.user.id.get, userRecipients, nonUserRecipients, urls, title, text)(contextBuilder.build)
      val messageThreadFut = messagingCommander.getThreadMessagesWithBasicUser(thread, None)
      val threadInfoOpt = (o \ "url").asOpt[String].map { url =>
        messagingCommander.buildThreadInfos(request.user.id.get, Seq(thread), Some(url)).headOption
      }.flatten

      messageThreadFut.map { case (_, messages) =>
        val tDiff = currentDateTime.getMillis - tStart.getMillis
        Statsd.timing(s"messaging.newMessage", tDiff)
        Ok(Json.obj(
          "id" -> message.externalId.id,
          "parentId" -> message.threadExtId.id,
          "createdAt" -> message.createdAt,
          "threadInfo" -> threadInfoOpt,
          "messages" -> messages.reverse))
      }
    }

    Async(messageSubmitResponse.flatMap(r => r)) // why scala.concurrent.Future doesn't have a .flatten is beyond me
  }

  private def recipientJsonToTypedFormat(rawRecipients: Seq[JsValue]) = {
    rawRecipients.foldLeft((Seq[ExternalId[User]](), Seq[NonUserParticipant]())) { case ((externalUserIds, nonUserParticipants), elem) =>
      elem.asOpt[String].flatMap(ExternalId.asOpt[User]) match {
        case Some(externalUserId) => (externalUserIds :+ externalUserId, nonUserParticipants)
        case None =>
          elem.asOpt[JsObject].flatMap { obj =>
            // The strategy is to get the identifier in the correct wrapping type, and pimp it with `constructNonUserRecipients` later
            (obj \ "kind").asOpt[String] match {
              case Some("email") if (obj \ "email").asOpt[String].isDefined =>
                Some(NonUserEmailParticipant(GenericEmailAddress((obj \ "email").as[String]), None))
              case _ => // Unsupported kind
                None
            }
          } match {
            case Some(nonUser) =>
              (externalUserIds, nonUserParticipants :+ nonUser)
            case None =>
              (externalUserIds, nonUserParticipants)
          }
      }
    }
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = AuthenticatedJsonToJsonAction { request =>
    val tStart = currentDateTime
    val o = request.body
    val text = (o \ "text").as[String].trim
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    (o \ "extVersion").asOpt[String].foreach { version => contextBuilder += ("extensionVersion", version) }
    val (_, message) = messagingCommander.sendMessage(request.user.id.get, threadExtId, text, None)(contextBuilder.build)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    Statsd.timing(s"messaging.replyMessage", tDiff)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }

  /*********** WEBSOCKETS ******************/

  protected def onConnect(socket: SocketInfo) : Unit = {
    notificationRouter.registerUserSocket(socket)
  }

  protected def onDisconnect(socket: SocketInfo) : Unit = {
    notificationRouter.unregisterUserSocket(socket)
  }

  //TEMPORARY STOP GAP
  val sideEffectingEvents = Set[String]("usefulPage", "sliderShown")

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
      messagingCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None) map { case (thread, msgs) =>
        log.info(s"[get_thread] got messages: $msgs")
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

    // pre-inbox notification/thread handlers (soon will be obsolete)

    "get_notifications" -> { case JsNumber(howMany) +: _ =>
      messagingCommander.getLatestSendableNotificationsNotJustFromMe(socket.userId, howMany.toInt).map{ notices =>
        val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
        socket.channel.push(Json.arr("notifications", notices, numUnreadUnmuted, END_OF_TIME))
      }
    },
    "get_missed_notifications" -> { case JsString(time) +: _ =>
      messagingCommander.getSendableNotificationsNotJustFromMeSince(socket.userId, parseStandardTime(time)).map{ notices =>
        socket.channel.push(Json.arr("missed_notifications", notices, currentDateTime))
      }
    },
    "get_old_notifications" -> { case JsNumber(requestId) +: JsString(time) +: JsNumber(howMany) +: _ =>
      messagingCommander.getSendableNotificationsNotJustFromMeBefore(socket.userId, parseStandardTime(time), howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "set_all_notifications_visited" -> { case JsString(notifId) +: _ =>
      val messageId = ExternalId[Message](notifId)
      val lastModified = messagingCommander.setAllNotificationsReadBefore(socket.userId, messageId)
      socket.channel.push(Json.arr("all_notifications_visited", notifId, lastModified))
    },
    "get_threads_by_url" -> { case JsString(url) +: _ =>  // deprecated in favor of "get_threads"
      messagingCommander.getThreadInfos(socket.userId, url).map{ case (_, threadInfos) =>
        socket.channel.push(Json.arr("thread_infos", threadInfos))
      }
    },
    "get_threads" -> { case JsNumber(requestId) +: JsString(url) +: _ =>
      messagingCommander.getThreadInfos(socket.userId, url).map { case (nUriStr, threadInfos) =>
        socket.channel.push(Json.arr(requestId.toLong, threadInfos, nUriStr))
      }
    },
    "get_thread_info" -> { case JsNumber(requestId) +: JsString(threadId) +: _ =>
      log.info(s"[get_thread_info] user ${socket.userId} requesting thread extId $threadId")
      val info = messagingCommander.getThreadInfo(socket.userId, ExternalId[MessageThread](threadId))
      socket.channel.push(Json.arr(requestId.toLong, info))
    },

    // inbox notification/thread handlers

    "get_latest_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      messagingCommander.getLatestSendableNotifications(socket.userId, howMany.toInt).map{ notices =>
        val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(socket.userId)
        socket.channel.push(Json.arr(requestId.toLong, notices, numUnreadUnmuted))
      }
    },
    "get_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      messagingCommander.getSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_threads_since" -> { case JsNumber(requestId) +: JsString(time) +: _ =>
      messagingCommander.getSendableNotificationsSince(socket.userId, parseStandardTime(time)).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices, currentDateTime))
      }
    },
    "get_unread_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      messagingCommander.getLatestUnreadSendableNotifications(socket.userId, howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_unread_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      messagingCommander.getUnreadSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_muted_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      messagingCommander.getLatestMutedSendableNotifications(socket.userId, howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_muted_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      messagingCommander.getMutedSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_sent_threads" -> { case JsNumber(requestId) +: JsNumber(howMany) +: _ =>
      messagingCommander.getLatestSentSendableNotifications(socket.userId, howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_sent_threads_before" -> { case JsNumber(requestId) +: JsNumber(howMany) +: JsString(time) +: _ =>
      messagingCommander.getSentSendableNotificationsBefore(socket.userId, parseStandardTime(time), howMany.toInt).map{ notices =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    "get_page_threads" -> { case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: _ =>
      messagingCommander.getLatestSendableNotificationsForPage(socket.userId, url, howMany.toInt).map { case (nUriStr, notices, numTotal, numUnreadUnmuted) =>
        socket.channel.push(Json.arr(requestId.toLong, nUriStr, notices, numTotal, numUnreadUnmuted))
      }
    },
    "get_page_threads_before" -> { case JsNumber(requestId) +: JsString(url) +: JsNumber(howMany) +: JsString(time) +: _ =>
      messagingCommander.getSendableNotificationsForPageBefore(socket.userId, url, parseStandardTime(time), howMany.toInt).map { case (notices) =>
        socket.channel.push(Json.arr(requestId.toLong, notices))
      }
    },
    // TODO: contextual marking read (e.g. all Sent threads)
    // "set_threads_read" -> { case JsString(messageId) +: _ =>
    //   val messageId = ExternalId[Message](messageId)
    //   val messageTime = messagingCommander.setAllNotificationsReadBefore(socket.userId, messageId)
    //   socket.channel.push(Json.arr("threads_read", messageId, messageTime))
    // },
    // end of inbox notification/thread handlers

    "set_message_read" -> { case JsString(messageId) +: _ =>
      val msgExtId = ExternalId[Message](messageId)
      val contextBuilder = authenticatedWebSocketsContextBuilder(socket)
      contextBuilder += ("global", false)
      implicit val context = contextBuilder.build
      messagingCommander.setNotificationReadForMessage(socket.userId, msgExtId)
      messagingCommander.setLastSeen(socket.userId, msgExtId)
    },
    "set_global_read" -> { case JsString(messageId) +: _ =>
      val msgExtId = ExternalId[Message](messageId)
      val contextBuilder = authenticatedWebSocketsContextBuilder(socket)
      contextBuilder += ("global", true)
      implicit val context = contextBuilder.build
      messagingCommander.setNotificationReadForMessage(socket.userId, msgExtId)
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
    "set_notfication_unread" -> { case JsString(threadId) +: _ =>
      messagingCommander.setNotificationUnread(socket.userId, ExternalId[MessageThread](threadId))
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
      if (sideEffectingEvents.contains(eventName)) shoebox.logEvent(socket.userId, eventJson)
      //else discard!
    }
  )
}

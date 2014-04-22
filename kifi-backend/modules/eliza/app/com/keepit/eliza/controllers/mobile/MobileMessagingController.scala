package com.keepit.eliza.controllers.mobile

import com.keepit.social.BasicUserLikeEntity._
import com.keepit.eliza.commanders._
import com.keepit.eliza.model.MessageThread
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.heimdal._

import play.modules.statsd.api.Statsd

import com.keepit.common.db.ExternalId

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.Future

import com.google.inject.Inject
import com.keepit.social.{BasicUserLikeEntity, BasicUser, BasicNonUser}
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import scala.Some
import play.api.libs.json.JsObject


class MobileMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  basicMessageCommander: MessageFetchingCommander,
  notificationCommander: NotificationCommander,
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory
  ) extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int, before: Option[String]) = JsonAction.authenticatedAsync { request =>
    val noticesFuture = before match {
      case Some(before) =>
        notificationCommander.getSendableNotificationsBefore(request.userId, parseStandardTime(before), howMany.toInt)
      case None =>
        notificationCommander.getLatestSendableNotifications(request.userId, howMany.toInt)
    }
    noticesFuture.map {notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices.jsons, numUnreadUnmuted))
    }
  }

  def sendMessageAction() = JsonAction.authenticatedParseJsonAsync { request =>
    val o = request.body
    val (title, text) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim
    )
    val (userExtRecipients, nonUserRecipients) = messagingCommander.recipientJsonToTypedFormat((o \ "recipients").as[Seq[JsValue]])
    val url = (o \ "url").asOpt[String]
    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "mobile")

    val messageSubmitResponse = messagingCommander.sendMessageAction(title, text,
        userExtRecipients, nonUserRecipients, url, urls, request.userId, contextBuilder.build) map { case (message, threadInfoOpt, messages) =>
      Ok(Json.obj(
        "id" -> message.externalId.id,
        "parentId" -> message.threadExtId.id,
        "createdAt" -> message.createdAt,
        "threadInfo" -> threadInfoOpt,
        "messages" -> messages.reverse))
    }

    messageSubmitResponse
  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = JsonAction.authenticatedParseJson { request =>
    val tStart = currentDateTime
    val o = request.body
    val text = (o \ "text").as[String].trim
    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "mobile")
    val (_, message) = messagingCommander.sendMessage(request.user.id.get, threadExtId, text, None)(contextBuilder.build)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    Statsd.timing(s"messaging.replyMessage", tDiff)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }


  def getThread(threadId: String) = JsonAction.authenticatedAsync { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None) map { case (thread, msgs) =>
      val url = thread.url.getOrElse("")  // needs to change when we have detached threads
      Ok(Json.obj("id" -> threadId, "uri" -> url, "messages" -> msgs.reverse))
    }
  }

  //todo(eishay): paginate
  def getCompactThread(threadId: String) = JsonAction.authenticatedAsync { request =>
    basicMessageCommander.getThreadMessagesWithBasicUser(ExternalId[MessageThread](threadId), None) map { case (thread, msgs) =>
      val url = thread.url.getOrElse("")  // needs to change when we have detached threads
      val nUrl = thread.nUrl.getOrElse("")  // needs to change when we have detached threads
      val participants: Set[BasicUserLikeEntity] = msgs.map(_.participants).flatten.toSet
      Ok(Json.obj(
        "id" -> threadId,
        "uri" -> url,
        "nUrl" -> nUrl,
        "participants" -> participants,
        "messages" -> (msgs.reverse map { m =>
          val adderAndAddedOpt: Option[(String,Seq[String])] = m.auxData match {
            case Some(JsArray(Seq(JsString("add_participants"), adderBasicUser, addedBasicUsers))) => Some( adderBasicUser.as[BasicUser].externalId.id -> addedBasicUsers.as[Seq[BasicUser]].map(_.externalId.id) )
            case _ => None
          }
          val baseJson = Json.obj(
            "id" -> m.id.toString,
            "time" -> m.createdAt.getMillis,
            "text" -> m.text
          )
          val msgJson = baseJson ++ (m.user match {
              case Some(bu: BasicUser) => Json.obj("userId" -> bu.externalId.toString)
              case Some(bnu: BasicNonUser) if bnu.kind=="email" => Json.obj("userId" -> bnu.toString)
              case _ => Json.obj()
          })

          adderAndAddedOpt.map { adderAndAdded =>
            msgJson.deepMerge(Json.obj("added" -> adderAndAdded._2, "userId" -> adderAndAdded._1))
          } getOrElse{
            msgJson
          }
        })
      ))
    }
  }

  def getThreadsByUrl(url: String) = JsonAction.authenticatedAsync { request =>
    messagingCommander.getThreadInfos(request.userId, url).map { case (_, threadInfos) =>
      Ok(Json.toJson(threadInfos))
    }
  }

  def hasThreadsByUrl(url: String) = JsonAction.authenticatedAsync { request =>
    messagingCommander.hasThreads(request.userId, url).map { yesorno  =>
      Ok(Json.toJson(yesorno))
    }
  }
}

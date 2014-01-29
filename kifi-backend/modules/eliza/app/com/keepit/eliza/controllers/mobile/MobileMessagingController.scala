package com.keepit.eliza.controllers.mobile

import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.eliza.model.MessageThread
import com.keepit.common.controller.{ElizaServiceController, MobileController, ActionAuthenticator}
import com.keepit.common.time._
import com.keepit.heimdal._

import play.modules.statsd.api.Statsd

import com.keepit.common.db.{Id, ExternalId}

import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import com.google.inject.Inject


class MobileMessagingController @Inject() (
  messagingCommander: MessagingCommander,
  actionAuthenticator: ActionAuthenticator,
  heimdalContextBuilder: HeimdalContextBuilderFactory
  ) extends MobileController(actionAuthenticator) with ElizaServiceController {

  def getNotifications(howMany: Int) = JsonAction.authenticatedAsync { request =>
    messagingCommander.getLatestSendableNotifications(request.userId, howMany.toInt).map{ notices =>
      val numUnreadUnmuted = messagingCommander.getUnreadUnmutedThreadCount(request.userId)
      Ok(Json.arr("notifications", notices, numUnreadUnmuted))
    }
  }


  def sendMessageAction() = JsonAction.authenticatedParseJsonAsync { request =>
    val tStart = currentDateTime
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
}

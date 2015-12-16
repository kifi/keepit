package com.keepit.eliza.controllers.ext

import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.discussion.Message
import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders.{ ElizaThreadInfo, ElizaDiscussionCommander, MessagingCommander, ElizaEmailCommander }
import com.keepit.common.db.ExternalId
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.search.SearchServiceClient
import com.keepit.common.mail.RemotePostOffice

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.{ JsSuccess, Json, JsValue, JsObject }

import akka.actor.ActorSystem

import com.google.inject.Inject
import com.keepit.common.logging.AccessLog
import scala.concurrent.Future

class ExtMessagingController @Inject() (
    discussionCommander: ElizaDiscussionCommander,
    messagingCommander: MessagingCommander,
    val userActionsHelper: UserActionsHelper,
    emailCommander: ElizaEmailCommander,
    implicit val publicIdConfig: PublicIdConfiguration,
    protected val clock: Clock,
    protected val airbrake: AirbrakeNotifier,
    protected val heimdal: HeimdalServiceClient,
    protected val heimdalContextBuilder: HeimdalContextBuilderFactory,
    val accessLog: AccessLog) extends UserActions with ElizaServiceController {

  def sendMessageAction() = UserAction.async(parse.tolerantJson) { request =>
    val o = request.body
    val extVersion = (o \ "extVersion").asOpt[String]
    val (title, text, source) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim,
      (o \ "source").asOpt[MessageSource]
    )
    val (validUserRecipients, validEmailRecipients, validOrgRecipients) = messagingCommander.parseRecipients((o \ "recipients").as[Seq[JsValue]]) // XXXX

    val url = (o \ "url").as[String]

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "extension")
    extVersion.foreach { version => contextBuilder += ("extensionVersion", version) }
    contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip
    if ((o \ "guided").asOpt[Boolean].getOrElse(false)) {
      contextBuilder += ("guided", true)
    }

    messagingCommander.sendMessageAction(title, text, source, validUserRecipients, validEmailRecipients, validOrgRecipients, url, request.userId, contextBuilder.build).map {
      case (message, threadInfo, messages) =>
        Ok(Json.obj(
          "id" -> message.pubId,
          "parentId" -> MessageThreadId.format.writes(threadInfo.threadId),
          "createdAt" -> message.createdAt,
          "threadInfo" -> ElizaThreadInfo.writesThreadInfo.writes(threadInfo),
          "messages" -> messages.reverse))
    }.recover {
      case ex: Exception if ex.getMessage == "insufficient_org_permissions" =>
        Forbidden(Json.obj("error" -> "insufficient_org_permissions"))
    }

  }

  def sendMessageReplyAction(threadIdStr: String) = UserAction.async(parse.tolerantJson) { request =>
    MessageThreadId.fromIdString(threadIdStr) match {
      case Some(threadId) =>
        val tStart = currentDateTime
        val o = request.body
        val (text, source) = (
          (o \ "text").as[String].trim,
          (o \ "source").asOpt[MessageSource]
        )
        val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
        contextBuilder += ("source", "extension")
        (o \ "extVersion").asOpt[String].foreach { version => contextBuilder += ("extensionVersion", version) }
        contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip
        discussionCommander.sendMessage(request.userId, text, threadId, source)(contextBuilder.build).map { message =>
          val tDiff = currentDateTime.getMillis - tStart.getMillis
          statsd.timing(s"messaging.replyMessage", tDiff, ONE_IN_HUNDRED)
          Ok(Json.obj("id" -> message.pubId, "parentId" -> threadIdStr, "createdAt" -> message.sentAt))
        }
      case None => Future.successful(BadRequest("invalid_thread_id"))
    }
  }

  def getEmailPreview(msgExtId: String) = UserAction.async { request =>
    emailCommander.getEmailPreview(ExternalId[ElizaMessage](msgExtId)).map(Ok(_))
  }

}

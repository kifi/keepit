package com.keepit.eliza.controllers.ext

import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.discussion.{ MessageSource, DiscussionFail, Message }
import com.keepit.eliza.model._
import com.keepit.eliza.commanders.{ ElizaThreadInfo, ElizaDiscussionCommander, MessagingCommander, ElizaEmailCommander }
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.model.Keep
import com.keepit.common.time._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.common.core._

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.{ Json, JsValue }

import com.google.inject.Inject
import com.keepit.common.logging.AccessLog
import scala.concurrent.Future
import scala.util.{ Failure, Success }

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

    implicit val time = CrossServiceTime(clock.now)
    messagingCommander.sendMessageAction(title, text, source, validUserRecipients, validEmailRecipients, validOrgRecipients, url, request.userId, contextBuilder.build).map {
      case (message, threadInfo, messages) =>
        Ok(Json.obj(
          "id" -> message.pubId,
          "parentId" -> threadInfo.keepId,
          "createdAt" -> message.createdAt,
          "threadInfo" -> ElizaThreadInfo.writesThreadInfo.writes(threadInfo),
          "messages" -> messages.reverse))
    }.recover {
      case ex: Exception if ex.getMessage == "insufficient_org_permissions" =>
        Forbidden(Json.obj("error" -> "insufficient_org_permissions"))
    }

  }

  def sendMessageReplyAction(pubKeepId: PublicId[Keep]) = UserAction.async(parse.tolerantJson) { request =>
    (for {
      keepId <- Keep.decodePublicId(pubKeepId).map(Future.successful).getOrElse(Future.failed(DiscussionFail.INVALID_KEEP_ID))
      message <- {
        val o = request.body
        val (text, source) = (
          (o \ "text").as[String].trim,
          (o \ "source").asOpt[MessageSource]
        )
        implicit val context = {
          val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
          contextBuilder += ("source", "extension")
          (o \ "extVersion").asOpt[String].foreach { version => contextBuilder += ("extensionVersion", version) }
          contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip //
          contextBuilder.build
        }
        implicit val time = CrossServiceTime(clock.now)
        discussionCommander.sendMessage(request.userId, text, keepId, source)
      }
    } yield {
      Ok(Json.obj("id" -> message.pubId, "parentId" -> pubKeepId, "createdAt" -> message.sentAt))
    }).recover {
      case f: DiscussionFail =>
        airbrake.notify(f)
        f.asErrorResponse
    }
  }

  def getEmailPreview(messagePubId: PublicId[Message]) = UserAction.async { request =>
    Message.decodePublicId(messagePubId) match {
      case Success(messageId) => emailCommander.getEmailPreview(ElizaMessage.fromCommonId(messageId)).imap(Ok(_))
      case Failure(_) => Future.successful(BadRequest("invalid_message_id"))
    }

  }

}

package com.keepit.eliza.controllers.ext

import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders.MessagingCommander
import com.keepit.common.db.ExternalId
import com.keepit.common.controller.{ElizaServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.controller.FortyTwoCookies.ImpersonateCookie
import com.keepit.common.time._
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.heimdal._
import com.keepit.search.SearchServiceClient
import com.keepit.common.crypto.SimpleDESCrypt
import com.keepit.common.mail.RemotePostOffice

import play.api.libs.concurrent.Execution.Implicits.defaultContext

import play.api.libs.json.{Json, JsValue, JsObject}
import play.modules.statsd.api.Statsd

import akka.actor.ActorSystem

import com.google.inject.Inject

class ExtMessagingController @Inject() (
    postOffice: RemotePostOffice,
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
  ) extends BrowserExtensionController(actionAuthenticator) with ElizaServiceController {

  def sendMessageAction() = JsonAction.authenticatedParseJsonAsync { request =>
    val tStart = currentDateTime
    val o = request.body
    val extVersion = (o \ "extVersion").asOpt[String]
    val (title, text) = (
      (o \ "title").asOpt[String],
      (o \ "text").as[String].trim
    )
    val (userExtRecipients, nonUserRecipients) = messagingCommander.recipientJsonToTypedFormat((o \ "recipients").as[Seq[JsValue]])
    val url = (o \ "url").asOpt[String]
    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "extension")
    extVersion.foreach { version => contextBuilder += ("extensionVersion", version) }
    contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip

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
    contextBuilder += ("source", "extension")
    (o \ "extVersion").asOpt[String].foreach { version => contextBuilder += ("extensionVersion", version) }
    contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip
    val (_, message) = messagingCommander.sendMessage(request.user.id.get, threadExtId, text, None)(contextBuilder.build)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    Statsd.timing(s"messaging.replyMessage", tDiff)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }
}

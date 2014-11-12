package com.keepit.eliza.controllers.ext

import com.keepit.eliza.model._
import com.keepit.eliza.controllers._
import com.keepit.eliza.commanders.{ MessagingCommander, ElizaEmailCommander }
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
    postOffice: RemotePostOffice,
    messagingCommander: MessagingCommander,
    val userActionsHelper: UserActionsHelper,
    notificationRouter: WebSocketRouter,
    amazonInstanceInfo: AmazonInstanceInfo,
    threadRepo: MessageThreadRepo,
    emailCommander: ElizaEmailCommander,
    protected val shoebox: ShoeboxServiceClient,
    protected val search: SearchServiceClient,
    protected val impersonateCookie: ImpersonateCookie,
    protected val actorSystem: ActorSystem,
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
    val (users, emailContacts) = messagingCommander.validateRecipients((o \ "recipients").as[Seq[JsValue]])

    val validUserRecipients = users.collect { case JsSuccess(validUser, _) => validUser }
    val validEmailRecipients = emailContacts.collect { case JsSuccess(validContact, _) => validContact }

    val url = (o \ "url").asOpt[String]
    val urls = JsObject(o.as[JsObject].value.filterKeys(Set("url", "canonical", "og").contains).toSeq)

    val contextBuilder = heimdalContextBuilder.withRequestInfo(request)
    contextBuilder += ("source", "extension")
    extVersion.foreach { version => contextBuilder += ("extensionVersion", version) }
    contextBuilder.data.remove("remoteAddress") // To be removed when the extension if fixed to send the client's ip
    if ((o \ "guided").asOpt[Boolean].getOrElse(false)) {
      contextBuilder += ("guided", true)
    }

    val messageSubmitResponse = messagingCommander.sendMessageAction(title, text, source,
      validUserRecipients, validEmailRecipients, url, urls, request.userId, contextBuilder.build) map {
        case (message, threadInfoOpt, messages) =>
          Ok(Json.obj(
            "id" -> message.externalId.id,
            "parentId" -> message.threadExtId.id,
            "createdAt" -> message.createdAt,
            "threadInfo" -> threadInfoOpt,
            "messages" -> messages.reverse))
      }

    messageSubmitResponse // todo(Martin, Jared, Léo): return meaningful error about invalid participants

  }

  def sendMessageReplyAction(threadExtId: ExternalId[MessageThread]) = UserAction(parse.tolerantJson) { request =>
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
    val (_, message) = messagingCommander.sendMessage(request.user.id.get, threadExtId, text, source, None)(contextBuilder.build)
    val tDiff = currentDateTime.getMillis - tStart.getMillis
    statsd.timing(s"messaging.replyMessage", tDiff, ONE_IN_HUNDRED)
    Ok(Json.obj("id" -> message.externalId.id, "parentId" -> message.threadExtId.id, "createdAt" -> message.createdAt))
  }

  def getEmailPreview(msgExtId: String) = UserAction.async { request =>
    emailCommander.getEmailPreview(ExternalId[Message](msgExtId)).map(Ok(_))
  }

}

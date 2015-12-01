package com.keepit.eliza.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.PublicIdConfiguration
import com.keepit.common.db.ExternalId
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.AccessLog
import com.keepit.common.time._
import com.keepit.eliza.commanders.{ ElizaDiscussionCommander, ElizaEmailCommander, MessagingCommander }
import com.keepit.eliza.model._
import com.keepit.heimdal._
import com.keepit.model.{ Keep, KeepCreateRequest, Library }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._

import scala.concurrent.Future

class ExtMessagingController @Inject() (
    messagingCommander: MessagingCommander,
    val userActionsHelper: UserActionsHelper,
    emailCommander: ElizaEmailCommander,
    shoebox: ShoeboxServiceClient,
    discussionCommander: ElizaDiscussionCommander,
    private implicit val publicIdConfiguration: PublicIdConfiguration,
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
      case (message, threadInfoOpt, messages) =>
        Ok(Json.obj(
          "id" -> message.externalId.id,
          "parentId" -> message.threadExtId.id,
          "createdAt" -> message.createdAt,
          "threadInfo" -> threadInfoOpt,
          "messages" -> messages.reverse))
    }.recover {
      case ex: Exception if ex.getMessage == "insufficient_org_permissions" =>
        Forbidden(Json.obj("error" -> "insufficient_org_permissions"))
    }

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
    emailCommander.getEmailPreview(ExternalId[ElizaMessage](msgExtId)).map(Ok(_))
  }

  def startDiscussion() = UserAction.async(parse.tolerantJson) { request =>
    implicit val context = heimdalContextBuilder.withRequestInfo(request).build
    request.body.validate[ExternalDiscussionCreateRequest] match {
      case JsError(fail) => Future.successful(BadRequest(Json.obj("error" -> "could_not_parse", "hint" -> fail.toString)))
      case JsSuccess(extReq, _) =>
        val extUserIds = extReq.users
        val libraryIds = extReq.libraries.flatMap(pubLibId => Library.decodePublicId(pubLibId).toOption)
        for {
          userIdByExtId <- shoebox.getUserIdsByExternalIds(extUserIds)
          csKeep <- shoebox.internKeep(KeepCreateRequest(
            owner = request.userId,
            users = extUserIds.flatMap(userIdByExtId.get),
            libraries = libraryIds,
            url = extReq.url,
            title = None,
            canonical = extReq.canonical,
            openGraph = None,
            keptAt = None,
            note = None))
          msg <- discussionCommander.sendMessageOnKeep(request.userId, extReq.initialMessage, csKeep.id, extReq.source)
        } yield Ok(Json.obj("id" -> msg.externalId.id, "parentId" -> msg.threadExtId.id, "createdAt" -> msg.createdAt, "keep" -> Keep.publicId(csKeep.id).id))
    }

  }

}

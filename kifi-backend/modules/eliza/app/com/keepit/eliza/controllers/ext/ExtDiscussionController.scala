package com.keepit.eliza.controllers.ext

import com.google.inject.Inject
import com.keepit.common.controller.{ ElizaServiceController, UserActions, UserActionsHelper }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.eliza.commanders.{ DiscussionCommander, MessagingCommander }
import com.keepit.model.RawDiscussion
import play.api.libs.json._

import scala.concurrent.{ Future, ExecutionContext }

class ExtDiscussionController @Inject() (
    discussionCommander: DiscussionCommander,
    val userActionsHelper: UserActionsHelper,
    implicit val defaultContext: ExecutionContext,
    protected val airbrake: AirbrakeNotifier) extends UserActions with ElizaServiceController {

  def startDiscussion() = UserAction.async(parse.tolerantJson) { implicit request =>
    val message = (request.body \ "message").asOpt[String].getOrElse("")
    (request.body \ "discussion").validate[RawDiscussion] match {
      case JsError(errs) =>
        airbrake.notify(s"Could not json-validate startDiscussion request from ${request.userId}: ${request.body}", new JsResultException(errs))
        Future.successful(BadRequest(Json.obj("error" -> "badly_formatted_request", "details" -> errs.toString)))
      case JsSuccess(rawDiscussion, _) =>
        discussionCommander.startDiscussionThread(rawDiscussion).map { d =>
          discussionCommander.sendMessageOnDiscussion(d.owner, d.keepId, message)
          Ok(JsString("yay"))
        }
    }
  }
}

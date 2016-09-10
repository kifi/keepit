package com.keepit.controllers.api

import com.google.inject.Inject
import play.api.mvc.Action
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import play.api.libs.json.{ JsError, JsSuccess, Json }
import com.keepit.commanders.{ SendgridCommander, SendgridEvent }
import scala.Exception

/**
 * see:
 * https://sendgrid.com/app/appSettings/type/eventnotify/id/15
 * http://sendgrid.com/docs/API_Reference/Webhooks/event.html
 * http://sendgrid.com/docs/API_Reference/Webhooks/parse.html
 */
class SendgridController @Inject() (
  val userActionsHelper: UserActionsHelper,
  sendgridCommander: SendgridCommander)
    extends UserActions with ShoeboxServiceController with Logging {

  def parseEvent() = Action(parse.tolerantJson) { request =>
    val events: Seq[SendgridEvent] = {
      val json = request.body
      Json.fromJson[Seq[SendgridEvent]](json) match {
        case JsSuccess(e, _) => e
        case JsError(errors) => throw new Exception(s"can't parse json: ${request.body.toString()} because ${errors mkString ","}")
      }
    }
    log.info(s"got a new event from sendgrid: ${request.body.toString()} with headers: ${request.headers.toMap}")
    log.info(s"there are ${events.size} events in the batch")
//    sendgridCommander.processNewEvents(events)
    Ok
  }
}

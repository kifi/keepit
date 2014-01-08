package com.keepit.controllers.api

import com.google.inject.Inject
import play.api.mvc.Action
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}
import play.api.libs.json.Json
import com.keepit.commanders.SendgridEvent

/**
 * see:
 * https://sendgrid.com/app/appSettings/type/eventnotify/id/15
 * http://sendgrid.com/docs/API_Reference/Webhooks/event.html
 * http://sendgrid.com/docs/API_Reference/Webhooks/parse.html
 */
class SendgridController  @Inject() (
  actionAuthenticator:ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) with Logging {

  def parseEvent() = Action(parse.json) { request =>
    val events: Seq[SendgridEvent] = Json.fromJson[Seq[SendgridEvent]](request.body).get
    log.info(s"got a new event from sendgrid: ${request.body.toString()} with headers: ${request.headers.toMap}")
    log.info(s"there are ${events.size} events in the batch")
    Ok
  }
}

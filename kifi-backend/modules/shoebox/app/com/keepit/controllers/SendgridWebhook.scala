package com.keepit.controllers

import com.google.inject.Inject
import play.api.mvc.Action
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}

/**
 * see:
 * http://sendgrid.com/docs/API_Reference/Webhooks/event.html
 * https://sendgrid.com/app/appSettings/type/eventnotify/id/15
 * http://sendgrid.com/docs/API_Reference/Webhooks/parse.html
 */
class SendgridWebhook  @Inject() (
  actionAuthenticator:ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) with Logging {

  def parseEvent() = Action(parse.json) { request =>
    log.info(s"got a new event from sendgrid: ${request.body.toString()}")
    Ok
  }
}

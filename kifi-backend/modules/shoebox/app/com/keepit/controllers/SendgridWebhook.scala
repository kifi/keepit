package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.common.db.Id
import com.keepit.model.User
import play.api.mvc.Action
import com.keepit.common.logging.Logging
import com.keepit.common.controller.{WebsiteController, ActionAuthenticator}

class SendgridWebhook  @Inject() (
  actionAuthenticator:ActionAuthenticator)
    extends WebsiteController(actionAuthenticator) with Logging {

  def parseEvent() = Action(parse.json) { request =>
    log.info(s"got a new event from sendgrid: ${request.body.toString()}")
    Ok
  }
}

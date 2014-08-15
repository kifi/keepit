package com.keepit.controllers.ext

import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.search.message.MessageSearchCommander

import play.api.libs.json.JsArray

import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.common.core._

class ExtMessageSearchController @Inject() (
    commander: MessageSearchCommander,
    actionAuthenticator: ActionAuthenticator) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  def search(query: String, page: Int) = JsonAction.authenticatedAsync { request =>
    if (page < 0) {
      Future.successful(BadRequest("Negative Page Number!"))
    } else {
      commander.search(request.userId, query, page, request.experiments).imap { hits => Ok(JsArray(hits)) }
    }
  }

}


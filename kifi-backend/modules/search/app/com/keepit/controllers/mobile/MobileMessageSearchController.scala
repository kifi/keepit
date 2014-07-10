package com.keepit.controllers.mobile

import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, ActionAuthenticator }
import com.keepit.common.logging.Logging
import com.keepit.search.message.MessageSearchCommander

import play.api.libs.json.JsArray

import com.google.inject.Inject

class MobileMessageSearchController @Inject() (
    commander: MessageSearchCommander,
    actionAuthenticator: ActionAuthenticator) extends BrowserExtensionController(actionAuthenticator) with SearchServiceController with Logging {

  def search(query: String, page: Int) = JsonAction.authenticated { request =>
    if (page < 0) {
      BadRequest("Negative Page Number!")
    } else {
      Ok(JsArray(commander.search(request.userId, query, page)))
    }
  }

}

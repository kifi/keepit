package com.keepit.controllers.mobile

import com.keepit.common.controller.{ SearchServiceController, BrowserExtensionController, UserActions, UserActionsHelper }
import com.keepit.common.logging.Logging
import com.keepit.search.message.MessageSearchCommander

import play.api.libs.json.JsArray

import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.common.core._

class MobileMessageSearchController @Inject() (
    commander: MessageSearchCommander,
    val userActionsHelper: UserActionsHelper) extends UserActions with SearchServiceController with Logging {

  def search(query: String, page: Int) = UserAction.async { request =>
    if (page < 0) {
      Future.successful(BadRequest("Negative Page Number!"))
    } else {
      commander.search(request.userId, query, page, request.experiments).imap { hits => Ok(JsArray(hits)) }
    }
  }
}

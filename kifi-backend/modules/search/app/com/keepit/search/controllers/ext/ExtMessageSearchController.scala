package com.keepit.search.controllers.ext

import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.common.logging.Logging
import com.keepit.search.index.message.MessageSearchCommander

import play.api.libs.json.JsArray

import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.common.core._

class ExtMessageSearchController @Inject() (
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


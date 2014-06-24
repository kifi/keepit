package com.keepit.controllers.search

import com.keepit.common.controller.{SearchServiceController, BrowserExtensionController, ActionAuthenticator}
import com.keepit.common.logging.Logging
import com.keepit.search.message.MessageSearchCommander
import com.keepit.common.db.Id
import com.keepit.model.User

import play.api.libs.json.JsArray
import play.api.mvc.Action

import com.google.inject.Inject


class MessageSearchController @Inject() (
    commander: MessageSearchCommander,
    actionAuthenticator: ActionAuthenticator
  ) extends SearchServiceController {

  def search(userId: Id[User], query: String, page: Int) = Action { request =>
    if (page < 0) {
      BadRequest("Negative Page Number!")
    } else {
      Ok(JsArray(commander.search(userId, query, page).map(_ \ "thread")))
    }
  }

}

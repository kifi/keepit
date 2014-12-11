package com.keepit.controllers.search

import com.keepit.common.controller.{ SearchServiceController, UserActions, UserActionsHelper }
import com.keepit.search.index.message.MessageSearchCommander
import com.keepit.common.db.Id
import com.keepit.model.User

import play.api.libs.json.JsArray
import play.api.mvc.Action

import com.google.inject.Inject
import scala.concurrent.Future
import com.keepit.commanders.RemoteUserExperimentCommander
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

class MessageSearchController @Inject() (
    commander: MessageSearchCommander,
    val userActionsHelper: UserActionsHelper,
    userExperimentCommander: RemoteUserExperimentCommander) extends SearchServiceController {

  def search(userId: Id[User], query: String, page: Int) = Action.async { request =>
    if (page < 0) {
      Future.successful(BadRequest("Negative Page Number!"))
    } else {
      userExperimentCommander.getExperimentsByUser(userId).flatMap { experiments =>
        commander.search(userId, query, page, experiments).imap {
          hits => Ok(JsArray(hits.map(_ \ "thread")))
        }
      }
    }
  }
}

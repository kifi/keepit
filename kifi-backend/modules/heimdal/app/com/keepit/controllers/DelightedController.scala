package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.DelightedCommander
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.Id
import com.keepit.common.mail.EmailAddress
import com.keepit.model._
import play.api.libs.json._
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class DelightedController @Inject() (
  delightedCommander: DelightedCommander) extends HeimdalServiceController {

  def getLastDelightedAnswerDate(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(delightedCommander.getLastDelightedAnswerDate(userId)))
  }

  def postDelightedAnswer(userId: Id[User], email: EmailAddress, score: Int, comment: Option[String]) = Action.async { request =>
    delightedCommander.postDelightedAnswer(userId, email, score, comment) map (Ok(_))
  }
}

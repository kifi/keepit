package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.DelightedCommander
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.{ExternalId, Id}
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

  def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String, score: Int, comment: Option[String]) = Action.async { request =>
    delightedCommander.postDelightedAnswer(userId, externalId, email, name, score, comment) map (Ok(_))
  }
}

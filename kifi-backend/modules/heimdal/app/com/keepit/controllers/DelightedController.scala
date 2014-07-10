package com.keepit.controllers

import com.google.inject.Inject
import com.keepit.commander.DelightedCommander
import com.keepit.common.controller.HeimdalServiceController
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.mail.EmailAddress
import com.keepit.heimdal.BasicDelightedAnswer
import com.keepit.model._
import play.api.libs.json._
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

class DelightedController @Inject() (
    delightedCommander: DelightedCommander,
    airbrake: AirbrakeNotifier) extends HeimdalServiceController {

  def getLastDelightedAnswerDate(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(delightedCommander.getLastDelightedAnswerDate(userId)))
  }

  def postDelightedAnswer(userId: Id[User], externalId: ExternalId[User], email: Option[EmailAddress], name: String) = Action.async(parse.tolerantJson) { request =>
    request.body.asOpt[BasicDelightedAnswer] map { answer =>
      delightedCommander.postDelightedAnswer(userId, externalId, email, name, answer) map (Ok(_))
    } getOrElse {
      airbrake.notify(s"Error parsing postDelightedAnswer request: ${request.body}")
      Future.successful(BadRequest)
    }
  }
}

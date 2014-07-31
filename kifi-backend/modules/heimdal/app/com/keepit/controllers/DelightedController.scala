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

  def getUserLastInteractedDate(userId: Id[User]) = Action { request =>
    Ok(Json.toJson(delightedCommander.getUserLastInteractedDate(userId)))
  }

  def postDelightedAnswer() = Action.async(parse.tolerantJson) { request =>
    val resultFutOpt = for {
      userRegistrationInfo <- (request.body \ "user").asOpt[DelightedUserRegistrationInfo]
      answer <- (request.body \ "answer").asOpt[BasicDelightedAnswer]
    } yield {
      delightedCommander.postDelightedAnswer(userRegistrationInfo, answer) map {
        case Left(error) => Ok(Json.obj("error" -> error))
        case Right(answer) => Ok(Json.toJson(BasicDelightedAnswer(Some(answer.score), answer.comment, answer.source, Some(answer.externalId))))
      }
    }
    resultFutOpt getOrElse {
      airbrake.notify(s"Error parsing postDelightedAnswer request: ${request.body}")
      Future.successful(BadRequest)
    }
  }

  def cancelDelightedSurvey() = Action.async(parse.tolerantJson) { request =>
    (request.body \ "user").asOpt[DelightedUserRegistrationInfo] map { userRegistrationInfo =>
      delightedCommander.cancelDelightedSurvey(userRegistrationInfo) map { success =>
        Ok(JsString(if (success) "success" else "error"))
      }
    } getOrElse {
      airbrake.notify(s"Error parsing cancelDelightedSurvey request: ${request.body}")
      Future.successful(BadRequest)
    }
  }
}

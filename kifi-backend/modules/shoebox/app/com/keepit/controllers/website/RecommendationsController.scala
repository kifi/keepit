package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.model.{ ScoreType, ExperimentType }
import com.keepit.model.ScoreType._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Results.Forbidden
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat

import scala.concurrent.Future

import com.google.inject.Inject

class RecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def adHocRecos(n: Int) = JsonAction.authenticatedAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.ADMIN)) {
      val body = request.body.asJson match {
        case Some(json) => json.as[Map[ScoreType.Value, Float]]
        case None => Map[ScoreType.Value, Float]()
      }
      commander.adHocRecos(request.userId, n, body).map(fkis => Ok(Json.toJson(fkis)))
    } else {
      Future.successful(Forbidden)
    }
  }
}

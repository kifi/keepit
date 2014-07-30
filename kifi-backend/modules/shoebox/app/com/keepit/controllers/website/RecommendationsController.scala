package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.model.ExperimentType

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc.Results.Forbidden

import scala.concurrent.Future

import com.google.inject.Inject

class RecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def adHocRecos(n: Int) = JsonAction.authenticatedAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.ADMIN)) {
      commander.adHocRecos(request.userId, n).map(fkis => Ok(Json.toJson(fkis)))
    } else {
      Future.successful(Forbidden)
    }
  }
}

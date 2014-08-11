package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.common.db.Id
import com.keepit.model._

import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json
import play.api.libs.json._
import play.api.mvc.Results.Forbidden

import scala.concurrent.Future

import com.google.inject.Inject

class RecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def adHocRecos(n: Int) = JsonAction.authenticatedParseJsonAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.ADMIN)) {
      val scores = request.body.as[UriRecommendationScores]
      commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
    } else {
      Future.successful(Forbidden)
    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = JsonAction.authenticatedParseJsonAsync { request =>
    val feedback = request.body.as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(userId, uriId, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI]) = JsonAction.authenticatedParseJsonAsync { request =>
    val interaction = request.body.as[UriRecommendationUserInteraction]
    commander.UriRecommendationUserInteraction(userId, uriId, interaction).map(fkis => Ok(Json.toJson(fkis)))
  }
}

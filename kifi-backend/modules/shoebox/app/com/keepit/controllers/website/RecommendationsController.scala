package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.common.db.slick.Database
import com.keepit.model._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json }
import scala.concurrent.Future
import com.google.inject.Inject

class RecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def adHocRecos(n: Int) = JsonAction.authenticatedParseJsonAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.RECOS_BETA)) {
      val scores = request.body.as[UriRecommendationScores]
      commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
    } else {
      Future.successful(Forbidden)
    }
  }

  def updateUriRecommendationFeedback(url: String) = JsonAction.authenticatedParseJsonAsync { request =>
    val feedback = request.body.as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(request.userId, url, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def updateUriRecommendationUserInteraction(url: String) = HtmlAction.authenticatedAsync { request =>
    val form = request.body.asFormUrlEncoded.get
    val vote = UriRecommendationUserInteraction(form.get("vote").flatMap(_.headOption.asInstanceOf[Option[Boolean]]))
    commander.updateUriRecommendationUserInteraction(request.userId, url, vote).map(fkis => Ok(Json.toJson(fkis)))
  }
}

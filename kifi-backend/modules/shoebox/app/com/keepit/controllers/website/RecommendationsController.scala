package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.model._
import com.keepit.normalizer.NormalizedURIInterner
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsNull, Json }
import scala.concurrent.Future
import com.google.inject.Inject

class RecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

  def adHocRecos(n: Int) = JsonAction.authenticatedParseJsonAsync { request =>
    if (userExperimentCommander.userHasExperiment(request.userId, ExperimentType.ADMIN)) {
      val scores = request.body.as[UriRecommendationScores]
      commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
    } else {
      Future.successful(Forbidden)
    }
  }

  def updateUriRecommendationFeedback(userId: Id[User], url: String) = JsonAction.authenticatedParseJsonAsync { request =>
    val feedback = request.body.as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(userId, url, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def updateUriRecommendationUserInteraction(userId: Id[User], url: String, vote: Option[Boolean]) = JsonAction.authenticatedParseJsonAsync { request =>
    commander.updateUriRecommendationUserInteraction(userId, url, vote).map(fkis => Ok(Json.toJson(fkis)))
  }
}

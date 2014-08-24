package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{ UriRecommendationScores, ExperimentType, UriRecommendationFeedback }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
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

  def topRecos(more: Boolean, recencyWeight: Float) = JsonAction.authenticatedParseJsonAsync { request =>
    commander.topRecos(request.userId, RecommendationClientType.Site, more, recencyWeight).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def topPublicRecos() = JsonAction.authenticatedParseJsonAsync { request =>
    commander.topPublicRecos().map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def updateUriRecommendationFeedback() = JsonAction.authenticatedParseJsonAsync { request =>
    val url = (request.body \ "url").as[String]
    val feedback = (request.body \ "feedback").as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(request.userId, url, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

}

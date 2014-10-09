package com.keepit.controllers.website

import com.keepit.common.controller.{ ShoeboxServiceController, ActionAuthenticator, WebsiteController }
import com.keepit.commanders.{ RecommendationsCommander, LocalUserExperimentCommander }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{ NormalizedURI, UriRecommendationScores, ExperimentType, UriRecommendationFeedback }
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
    val scores = request.body.as[UriRecommendationScores]
    commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
  }

  def topRecos(more: Boolean, recencyWeight: Float) = JsonAction.authenticatedAsync { request =>
    commander.topRecos(request.userId, RecommendationClientType.Site, more, recencyWeight).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def topPublicRecos() = JsonAction.authenticatedAsync { request =>
    commander.topPublicRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def updateUriRecommendationFeedback(id: ExternalId[NormalizedURI]) = JsonAction.authenticatedParseJsonAsync { request =>
    val feedback = request.body.as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def trash(id: ExternalId[NormalizedURI]) = JsonAction.authenticatedAsync { request =>
    commander.updateUriRecommendationFeedback(request.userId, id, UriRecommendationFeedback(trashed = Some(true))).map(fkis => Ok(Json.toJson(fkis)))
  }

}

package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ UserActionsHelper, ActionAuthenticator, ShoeboxServiceController, UserActions, WebsiteController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{ NormalizedURI, UriRecommendationFeedback, UriRecommendationScores }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class RecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController with UserActions {

  def adHocRecos(n: Int) = JsonAction.authenticatedParseJsonAsync { request =>
    val scores = request.body.as[UriRecommendationScores]
    commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
  }

  def topRecos(more: Boolean, recencyWeight: Float) = UserAction.async { request =>
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

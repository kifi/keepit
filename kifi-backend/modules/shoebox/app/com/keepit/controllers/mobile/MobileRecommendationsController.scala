package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{LocalUserExperimentCommander, RecommendationsCommander}
import com.keepit.common.controller.{ActionAuthenticator, ShoeboxServiceController, WebsiteController}
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{NormalizedURI, UriRecommendationFeedback}
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class MobileRecommendationsController @Inject() (
    actionAuthenticator: ActionAuthenticator,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends WebsiteController(actionAuthenticator) with ShoeboxServiceController {

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

  def trash(id: ExternalId[NormalizedURI]) = JsonAction.authenticatedAsync { request =>
    commander.updateUriRecommendationFeedback(request.userId, id, UriRecommendationFeedback(trashed = Some(true))).map(fkis => Ok(Json.toJson(fkis)))
  }

}

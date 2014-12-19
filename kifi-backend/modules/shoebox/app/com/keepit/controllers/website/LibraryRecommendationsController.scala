package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.RecommendationSource
import com.keepit.model.{ NormalizedURI, UriRecommendationFeedback, UriRecommendationScores }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class LibraryRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends UserActions with ShoeboxServiceController {

  def topLibRecos() = UserAction.async { request =>
    commander.topPublicLibraryRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

}

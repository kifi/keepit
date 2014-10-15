package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ UserActions, UserActionsHelper, ShoeboxServiceController, WebsiteController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{ NormalizedURI, UriRecommendationFeedback }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json

class MobileRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database) extends UserActions with ShoeboxServiceController {

  def topRecos(more: Boolean, recencyWeight: Float) = UserAction.async { request =>
    val agent = UserAgent(request)
    val recommendationClientType = if (agent.isKifiAndroidApp) {
      RecommendationClientType.Android
    } else if (agent.isKifiIphoneApp) {
      RecommendationClientType.IOS
    } else throw new IllegalArgumentException(s"the user agent is not of a kifi application: $agent")
    commander.topRecos(request.userId, recommendationClientType, more, recencyWeight).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def topPublicRecos() = UserAction.async { request =>
    commander.topPublicRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    commander.updateUriRecommendationFeedback(request.userId, id, UriRecommendationFeedback(trashed = Some(true))).map(fkis => Ok(Json.toJson(fkis)))
  }

}

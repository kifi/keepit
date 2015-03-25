package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper, UserRequest }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.keepit.model.{ Library, LibraryRecommendationFeedback }
import com.keepit.common.net.UserAgent
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsValue, Json }
import play.api.mvc.Action

import scala.concurrent.Future

class LibraryRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def topLibRecos() = UserAction.async { request =>
    commander.topPublicLibraryRecos(request.userId, limit = 5, source = RecommendationSource.Site,
      subSource = RecommendationSubSource.RecommendationsFeed, trackDelivery = true, context = None).map { recoResults =>
        Ok(Json.toJson(recoResults.recos.map(_._2)))
      }
  }

  private def getRecommendationSource(request: UserRequest[_]): RecommendationSource = {
    val agent = UserAgent(request)
    if (agent.isKifiAndroidApp) RecommendationSource.Android
    else if (agent.isKifiIphoneApp) RecommendationSource.IOS
    else RecommendationSource.Site
  }

  def updateLibraryRecommendationFeedback(pubId: PublicId[Library]): Action[JsValue] = UserAction.async[JsValue](parse.tolerantJson) { request =>
    Library.decodePublicId(pubId) map { id =>
      val feedback = request.body.as[LibraryRecommendationFeedback].
        copy(source = Some(getRecommendationSource(request)), subSource = Some(RecommendationSubSource.RecommendationsFeed))
      commander.updateLibraryRecommendationFeedback(request.userId, id, feedback).map(bool => Ok(Json.toJson(bool)))
    } getOrElse Future.successful(BadRequest(Json.obj("error" -> "bad library ID")))
  }

}

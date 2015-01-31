package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicId, PublicIdConfiguration }
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ RecommendationSubSource, RecommendationSource }
import com.keepit.model.{ Library, LibraryRecommendationFeedback }
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
    commander.topPublicLibraryRecos(request.userId, limit = 5, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed).map { recos =>
      Ok(Json.toJson(recos.map(_._2)))
    }
  }

  def updateLibraryRecommendationFeedback(pubId: PublicId[Library]): Action[JsValue] = UserAction.async[JsValue](parse.tolerantJson) { request =>
    Library.decodePublicId(pubId) map { id =>
      val feedback = request.body.as[LibraryRecommendationFeedback].
        copy(source = Some(RecommendationSource.Site), subSource = Some(RecommendationSubSource.RecommendationsFeed))
      commander.updateLibraryRecommendationFeedback(request.userId, id, feedback).map(bool => Ok(Json.toJson(bool)))
    } getOrElse Future.successful(BadRequest(Json.obj("error" -> "bad library ID")))
  }

}

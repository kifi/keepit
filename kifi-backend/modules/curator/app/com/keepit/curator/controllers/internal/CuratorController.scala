package com.keepit.curator.controllers.internal

import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.model.ScoreType.ScoreType
import com.keepit.model.ScoreType.ScoreType
import com.keepit.model.UriRecommendationFeedback._
import com.keepit.model.{ NormalizedURI, ScoreType, User }
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat
import com.keepit.common.util.MapFormatUtil.uriRecommendationFeedbackMapFormat

import play.api.mvc.Action
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import scala.concurrent.Future

class CuratorController @Inject() (recoGenCommander: RecommendationGenerationCommander) extends CuratorServiceController {

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[Map[ScoreType.Value, Float]]
      case None => Map[ScoreType.Value, Float]()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson
    json match {
      case Some(json) => {
        val feedback = json.as[Map[UriRecommendationFeedback, Boolean]]
        recoGenCommander.updateUriRecommendationFeedback(userId, uriId, feedback).map(update => Ok(Json.toJson(update)))
      }
      case None => Future.successful(Ok(Json.toJson(false)))
    }

  }
}

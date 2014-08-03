package com.keepit.curator.controllers.internal

import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.model.ScoreType.ScoreType
import com.keepit.model.ScoreType.ScoreType
import com.keepit.model.{ ScoreType, User }
import com.keepit.common.util.MapFormatUtil.scoreTypeMapFormat

import play.api.mvc.Action
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

class CuratorController @Inject() (recoGenCommander: RecommendationGenerationCommander) extends CuratorServiceController {

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocAdminRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[Map[ScoreType.Value, Float]]
      case None => Map[ScoreType.Value, Float]()
    }).map(recos => Ok(Json.toJson(recos)))
  }

}

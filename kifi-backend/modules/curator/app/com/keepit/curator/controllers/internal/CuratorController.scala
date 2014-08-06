package com.keepit.curator.controllers.internal

import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.model._

import play.api.mvc.Action
import play.api.libs.json.Json
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import scala.concurrent.Future

class CuratorController @Inject() (recoGenCommander: RecommendationGenerationCommander) extends CuratorServiceController {

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[UriRecommendationScores]
      case None => UriRecommendationScores()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson
    json match {
      case Some(json) => {
        val feedback = json.as[UriRecommendationFeedback]
        recoGenCommander.updateUriRecommendationFeedback(userId, uriId, feedback).map(update => Ok(Json.toJson(update)))
      }
      case None => Future.successful(Ok(Json.toJson(false)))
    }

  }

  def updateUriRecommendationUserInteraction(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson
    json match {
      case Some(json) => {
        val interaction = json.as[UriRecommendationUserInteraction]
        recoGenCommander.updateUriRecommendationUserInteraction(userId, uriId, interaction).map(update => Ok(Json.toJson(update)))
      }
      case None => Future.successful(Ok(Json.toJson(false)))
    }

  }
}

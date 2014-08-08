package com.keepit.curator.controllers.internal

import com.keepit.curator.commanders.RecommendationGenerationCommander
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.curator.commanders.email.EngagementFeedEmailSender
import com.keepit.model._

import play.api.mvc.Action
import play.api.libs.json.{ JsString, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import com.google.inject.Inject

import concurrent.Future

class CuratorController @Inject() (
    recoGenCommander: RecommendationGenerationCommander,
    engagaementFeedEmailSender: EngagementFeedEmailSender) extends CuratorServiceController {

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[UriRecommendationScores]
      case None => UriRecommendationScores()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson.get
    recoGenCommander.updateUriRecommendationFeedback(userId, uriId, json.as[UriRecommendationFeedback]).map(update => Ok(Json.toJson(update)))
  }

  def triggerEmail(code: String) = Action.async { request =>
    code match {
      case "feed" =>
        engagaementFeedEmailSender.send().map { res =>
          Ok(s"sent ${res.size} emails")
        }
      case _ => Future.successful(Ok(s"code $code not found"))
    }
  }
}

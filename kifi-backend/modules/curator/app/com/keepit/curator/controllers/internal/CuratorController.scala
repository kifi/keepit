package com.keepit.curator.controllers.internal

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.SafeFuture
import com.keepit.common.controller.CuratorServiceController
import com.keepit.common.db.Id
import com.keepit.curator.commanders.email.FeedDigestMessage.{ Queue, Send }
import com.keepit.curator.commanders.email.{ EngagementEmailActor, FeedDigestEmailSender }
import com.keepit.curator.commanders.{ CuratorAnalytics, RecommendationFeedbackCommander, RecommendationGenerationCommander, RecommendationRetrievalCommander, SeedIngestionCommander }
import com.keepit.curator.model.RecommendationClientType
import com.keepit.model.{ User, UriRecommendationScores, NormalizedURI, UriRecommendationFeedback }
import com.keepit.shoebox.ShoeboxServiceClient
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, JsString, Json }
import play.api.mvc.Action

import concurrent.Future

class CuratorController @Inject() (
    shoebox: ShoeboxServiceClient,
    curatorAnalytics: CuratorAnalytics,
    recoGenCommander: RecommendationGenerationCommander,
    seedCommander: SeedIngestionCommander,
    recoFeedbackCommander: RecommendationFeedbackCommander,
    recoRetrievalCommander: RecommendationRetrievalCommander,
    feedDigestActor: ActorInstance[EngagementEmailActor],
    feedEmailSender: FeedDigestEmailSender) extends CuratorServiceController {

  def adHocRecos(userId: Id[User], n: Int) = Action.async { request =>
    recoGenCommander.getAdHocRecommendations(userId, n, request.body.asJson match {
      case Some(json) => json.as[UriRecommendationScores]
      case None => UriRecommendationScores()
    }).map(recos => Ok(Json.toJson(recos)))
  }

  def topRecos(userId: Id[User]) = Action(parse.tolerantJson) { request =>
    val clientType = (request.body \ "clientType").as[RecommendationClientType]
    val more = (request.body \ "more").as[Boolean]
    val recencyWeight = (request.body \ "recencyWeight").as[Float]
    Ok(Json.toJson(recoRetrievalCommander.topRecos(userId, more, recencyWeight, clientType)))
  }

  def topPublicRecos() = Action { request =>
    Ok(Json.toJson(recoRetrievalCommander.topPublicRecos()))
  }

  def updateUriRecommendationFeedback(userId: Id[User], uriId: Id[NormalizedURI]) = Action.async { request =>
    val json = request.body.asJson.get
    val feedback = json.as[UriRecommendationFeedback]
    curatorAnalytics.trackUserFeedback(userId, uriId, feedback) // WARN: this has to happen before next line (due to read/write of UriRecommendationRepo)
    recoFeedbackCommander.updateUriRecommendationFeedback(userId, uriId, feedback).map(update => Ok(Json.toJson(update)))
  }

  def triggerEmailToUser(code: String, userId: Id[User]) = Action {
    code match {
      case "feed" =>
        log.info(s"trigger email $code to user $userId")
        NoContent
      case _ =>
        log.warn(s"trigger email error: code $code not found")
        BadRequest
    }
  }

  def triggerEmail(code: String) = Action {
    code match {
      case "feed" =>
        feedDigestActor.ref ! Queue
        NoContent
      case _ =>
        log.warn(s"trigger email error: code $code not found")
        BadRequest
    }
  }

  def refreshUserRecos(userId: Id[User]) = Action { request =>
    SafeFuture(seedCommander.forceIngestGraphData(userId), Some("Force ingesting Graph Data to refresh Recos"))
    Ok
  }
}

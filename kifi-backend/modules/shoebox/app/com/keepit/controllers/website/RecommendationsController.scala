package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.curator.model.{ FullLibRecoResults, FullUriRecoResults, RecommendationSubSource, RecommendationSource }
import com.keepit.model.{ LibraryRecommendationFeedback, Library, ExperimentType, NormalizedURI, UriRecommendationFeedback, UriRecommendationScores }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, Json }

import scala.concurrent.Future

class RecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController {

  def adHocRecos(n: Int) = UserAction.async(parse.tolerantJson) { request =>
    val scores = request.body.as[UriRecommendationScores]
    commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
  }

  def topRecosV2(recencyWeight: Float, uriContext: Option[String], libContext: Option[String]) = UserAction.async { request =>
    val libRecosF = commander.topPublicLibraryRecos(request.userId, 5, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, context = uriContext)
    val uriRecosF = commander.topRecos(request.userId, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, libContext.isDefined, recencyWeight, context = libContext)

    for (libs <- libRecosF; uris <- uriRecosF) yield Ok {
      val FullUriRecoResults(urisReco, newUriContext) = uris
      val FullLibRecoResults(libsReco, newLibContext) = libs
      val shuffled = util.Random.shuffle(urisReco ++ libsReco.map(_._2))
      Json.obj("recos" -> shuffled, "uctx" -> newUriContext, "lctx" -> newLibContext)
    }
  }

  def topPublicRecos() = UserAction.async { request =>
    commander.topPublicRecos(request.userId).map { recos =>
      Ok(Json.toJson(recos))
    }
  }

  def updateUriRecommendationFeedback(id: ExternalId[NormalizedURI]) = UserAction.async(parse.tolerantJson) { request =>
    val feedback = request.body.as[UriRecommendationFeedback]
    commander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    commander.updateUriRecommendationFeedback(request.userId, id, UriRecommendationFeedback(trashed = Some(true))).map(fkis => Ok(Json.toJson(fkis)))
  }

}

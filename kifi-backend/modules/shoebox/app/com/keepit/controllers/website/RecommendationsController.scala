package com.keepit.controllers.website

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.common.crypto.{ PublicIdConfiguration, PublicId }
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.curator.model._
import com.keepit.model.{ LibraryRecommendationFeedback, Library, ExperimentType, NormalizedURI, UriRecommendationFeedback, UriRecommendationScores }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsArray, Json }

import scala.concurrent.Future

class RecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    db: Database,
    implicit val publicIdConfig: PublicIdConfiguration) extends UserActions with ShoeboxServiceController with RecoMixingHelper {

  def adHocRecos(n: Int) = UserAction.async(parse.tolerantJson) { request =>
    val scores = request.body.as[UriRecommendationScores]
    commander.adHocRecos(request.userId, n, scores).map(fkis => Ok(Json.toJson(fkis)))
  }

  def topRecosV2(recencyWeight: Float, uriContext: Option[String], libContext: Option[String]) = UserAction.async { request =>
    log.info(s"uriContex: ${uriContext.getOrElse("null")}, libContext: ${libContext.getOrElse("null")}")
    val libRecosF = commander.topPublicLibraryRecos(request.userId, 10, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, context = uriContext)
    val uriRecosF = commander.topRecos(request.userId, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, libContext.isDefined, recencyWeight, context = libContext)

    for (libs <- libRecosF; uris <- uriRecosF) yield Ok {
      val FullUriRecoResults(urisReco, newUriContext) = uris
      val FullLibRecoResults(libsReco, newLibContext) = libs
      val recos = mix(urisReco, libsReco)
      Json.obj("recos" -> recos, "uctx" -> newUriContext, "lctx" -> newLibContext)
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

trait RecoMixingHelper extends Logging {
  // hack, make sure Danny is happy.
  private val specialBoostSet = Set(46862, 36680, 49078, 24103, 47498, 51798, 47889, 47191, 47494, 48661, 49090, 50874, 49135, 26460, 27238, 25168, 50812, 47488, 42651, 27760, 25368, 44475, 24203, 50862, 47284, 25000, 27545, 51496, 27049, 26465).map { Id[Library](_) }

  def mix(uris: Seq[FullUriRecoInfo], libs: Seq[(Id[Library], FullLibRecoInfo)]): Seq[FullRecoInfo] = {
    val (lucky, rest) = libs.partition { case (id, _) => specialBoostSet.contains(id) }
    log.info(s"[reco mixing] lucky library ids: ${lucky.map(_._1).mkString(", ")}. total libs size: ${libs.size}")
    util.Random.shuffle(lucky.map { _._2 }) ++ util.Random.shuffle(uris ++ rest.map(_._2))
  }
}

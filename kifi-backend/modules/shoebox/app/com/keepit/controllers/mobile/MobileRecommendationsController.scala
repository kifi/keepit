package com.keepit.controllers.mobile

import com.google.inject.Inject
import com.keepit.commanders.{ LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.db.ExternalId
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.controllers.website.RecommendationControllerHelper
import com.keepit.curator.model.{ FullLibRecoResults, FullUriRecoResults, RecommendationSubSource, RecommendationSource }
import com.keepit.model._
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.Result

import scala.concurrent.Future

class MobileRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    commander: RecommendationsCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    val db: Database,
    val userRepo: UserRepo,
    val libMemRepo: LibraryMembershipRepo) extends UserActions with ShoeboxServiceController with RecommendationControllerHelper {

  def topRecosV2(recencyWeight: Float, more: Boolean) = UserAction.async { request =>
    val uriRecosF = commander.topRecos(request.userId, getRecommendationSource(request), RecommendationSubSource.RecommendationsFeed, more, recencyWeight, None)
    val libRecosF = commander.topPublicLibraryRecos(request.userId, 10, RecommendationSource.Site, RecommendationSubSource.RecommendationsFeed, context = None)

    for (libs <- libRecosF; uris <- uriRecosF) yield Ok {
      val FullUriRecoResults(urisReco, _) = uris
      val FullLibRecoResults(libsReco, _) = libs
      val shuffled = mix(urisReco, libsReco)
      Json.toJson(shuffled)
    }
  }

  @json case class RecosRequest(recencyWeight: Float, uriContext: Option[String], libContext: Option[String])
  def topRecosV3Post() = UserAction.async { implicit request =>
    request.body.asJson.flatMap(_.asOpt[RecosRequest]) match {
      case None =>
        Future.successful(BadRequest(JsString("bad format for POST request")))
      case Some(req) =>
        topRecosV3(req.recencyWeight, req.uriContext, req.libContext, None, None)(request)
    }
  }

  // _uctx and _lctx are meant to support an iOS bug. Should not be supported in the future.
  def topRecosV3(recencyWeight: Float, uriContext: Option[String], libContext: Option[String], uctx: Option[String], lctx: Option[String]) = UserAction.async { request =>
    val contextBankruptcyLength = 3000 // 4k is max request size, leaves some room for other params
    val uriContext2 = (uriContext orElse uctx).flatMap(ctx => if (ctx.length > contextBankruptcyLength) None else Some(ctx))
    val libContext2 = (libContext orElse lctx).flatMap(ctx => if (ctx.length > contextBankruptcyLength) None else Some(ctx))
    log.info(s"mobile reco for user: ${request.userId}, urictx: ${uriContext2.map(_.length).getOrElse(0)}, libctx: ${libContext2.map(_.length).getOrElse(0)}")
    log.info(s"uriContext: ${uriContext2.getOrElse("n/a")}")
    log.info(s"libContext: ${libContext2.getOrElse("n/a")}")

    val libCnt = libraryRecoCount(request.userId)

    val uriRecosF = commander.topRecos(request.userId, getRecommendationSource(request), RecommendationSubSource.RecommendationsFeed, uriContext.isDefined, recencyWeight, context = uriContext2)
    val libRecosF = commander.topPublicLibraryRecos(request.userId, libCnt, getRecommendationSource(request), RecommendationSubSource.RecommendationsFeed, context = libContext2)

    for (libs <- libRecosF; uris <- uriRecosF) yield {
      val FullUriRecoResults(urisReco, newUrisContext) = uris
      val FullLibRecoResults(libsReco, newLibsContext) = libs
      val recos = mix(urisReco, libsReco)

      log.info(s"newUrisContext: ${newUrisContext}")
      log.info(s"newLibsContext: ${newLibsContext}")

      Ok(Json.obj("recos" -> recos, "uctx" -> newUrisContext, "lctx" -> newLibsContext))
    }
  }

  def topPublicRecos() = UserAction.async { request =>
    val useDict = request.headers.get("X-Kifi-Client").exists(_.toLowerCase.trim.startsWith("ios 2.1"))
    commander.topPublicRecos(request.userId).map { recos =>
      if (useDict) Ok(Json.obj("recos" -> recos))
      else Ok(Json.toJson(recos))
    }
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    val feedback = UriRecommendationFeedback(trashed = Some(true), source = Some(getRecommendationSource(request)),
      subSource = Some(RecommendationSubSource.RecommendationsFeed))
    commander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  private def getRecommendationSource(request: UserRequest[_]): RecommendationSource = {
    val agent = UserAgent(request)
    if (agent.isKifiAndroidApp) RecommendationSource.Android
    else if (agent.isKifiIphoneApp) RecommendationSource.IOS
    else throw new IllegalArgumentException(s"the user agent is not of a kifi application: $agent")
  }

}

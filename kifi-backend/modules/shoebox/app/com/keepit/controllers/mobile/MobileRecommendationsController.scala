package com.keepit.controllers.mobile

import com.keepit.common.time._
import com.google.inject.Inject
import com.keepit.commanders.{ LibraryInfoCommander, KeepCommander, LocalUserExperimentCommander, RecommendationsCommander }
import com.keepit.common.controller.{ UserRequest, UserActions, UserActionsHelper, ShoeboxServiceController }
import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{ Id, ExternalId }
import com.keepit.common.db.slick.Database
import com.keepit.common.net.UserAgent
import com.keepit.controllers.website.RecommendationControllerHelper
import com.keepit.curator.model._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ JsString, Json }
import play.api.mvc.Result

import scala.concurrent.Future

class MobileRecommendationsController @Inject() (
    val userActionsHelper: UserActionsHelper,
    recommendationsCommander: RecommendationsCommander,
    keepsCommander: KeepCommander,
    userExperimentCommander: LocalUserExperimentCommander,
    libraryInfoCommander: LibraryInfoCommander,
    normalizedURIRepo: NormalizedURIRepo,
    val db: Database,
    val userRepo: UserRepo,
    val keepRepo: KeepRepo,
    val libMemRepo: LibraryMembershipRepo) extends UserActions with ShoeboxServiceController with RecommendationControllerHelper {

  def topRecosV2(recencyWeight: Float, more: Boolean) = UserAction.async { request =>
    getKeepStreamAsRecos(request.userId, 10, None) map {
      case (recos, _) =>
        val json = Json.toJson(recos)
        Ok(json)
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

    val uriContext2 = uriContext orElse uctx
    val libContext2 = libContext orElse lctx
    feedFromStream(uriContext2, libContext2, request.userId)
  }

  def topPublicRecos() = UserAction.async { request =>
    val useDict = request.headers.get("X-Kifi-Client").exists(_.toLowerCase.trim.startsWith("ios 2.1"))
    recommendationsCommander.topPublicRecos(request.userId).map { recos =>
      if (useDict) Ok(Json.obj("recos" -> recos))
      else Ok(Json.toJson(recos))
    }
  }

  def trash(id: ExternalId[NormalizedURI]) = UserAction.async { request =>
    val feedback = UriRecommendationFeedback(trashed = Some(true), source = Some(getRecommendationSource(request)),
      subSource = Some(RecommendationSubSource.RecommendationsFeed))
    recommendationsCommander.updateUriRecommendationFeedback(request.userId, id, feedback).map(fkis => Ok(Json.toJson(fkis)))
  }

  def feedV1Post() = UserAction.async { request =>
    request.body.asJson.flatMap(_.asOpt[RecosRequest]) match {
      case None =>
        Future.successful(BadRequest(JsString("bad format for POST request")))
      case Some(req) =>
        feedFromStreamWithRequest(req)(request)
    }
  }

  private def feedFromStreamWithRequest(req: RecosRequest) = UserAction.async { request =>
    feedFromStream(req.uriContext, req.libContext, request.userId)
  }

  private def getKeepStreamAsRecos(userId: Id[User], limit: Int, beforeExtId: Option[ExternalId[Keep]]) = keepsCommander.getKeepStream(userId, limit, beforeExtId, None).map { updatedKeepsWithOptId =>
    val updatedKeeps = updatedKeepsWithOptId.filterNot(k => k.id.isEmpty || k.summary.isEmpty)
    val keepIdToUriId = db.readOnlyReplica { implicit session =>
      val allKeeps: Map[ExternalId[Keep], Option[Keep]] = keepRepo.getByExtIds(updatedKeeps.map(k => k.id.get).toSet)
      val keepIdToUriId = allKeeps collect {
        case (extId, keepOpt) if keepOpt.isDefined => //not cached
          val uri = normalizedURIRepo.get(keepOpt.get.uriId) //cached
          extId -> uri.externalId
      }
      keepIdToUriId
    }

    val goodUriContext = updatedKeeps.sortBy(_.createdAt.getOrElse(END_OF_TIME)).headOption.map(_.id.get.id).getOrElse("") //oldest keep external id
    val keepRecos = updatedKeeps.map { keep =>
      FullUriRecoInfo(metaData = None, itemInfo = UriRecoItemInfo(id = keepIdToUriId(keep.id.get),
        title = keep.title,
        url = keep.url,
        keepers = keep.keepers.getOrElse(Seq.empty),
        libraries = keep.libraries.map(_.map { case (lib, user) => RecoLibraryInfo(user, lib.id, lib.name, lib.path, lib.color.map(_.hex)) } toSeq).getOrElse(Seq.empty),
        others = Math.max(0, keep.keepersTotal.getOrElse(0) - keep.keepers.map(_.size).getOrElse(0)),
        siteName = keep.siteName,
        summary = keep.summary.get), explain = None)
    }
    keepRecos -> goodUriContext
  }

  private def feedFromStream(uriContext: Option[String], libContext: Option[String], userId: Id[User]) = {
    val beforeExtId = uriContext.flatMap(id => ExternalId.asOpt[Keep](id))
    val sentLibs: Boolean = libContext.exists(_ == "sentLibs")
    val defaultLimit = 10

    val libsF: Future[Seq[FullLibRecoInfo]] = recommendationsCommander.curatedPublicLibraryRecos(userId).map(_.map(_._2))
    val updatedKeepsF = getKeepStreamAsRecos(userId, defaultLimit, beforeExtId)

    for {
      libs <- libsF
      (keepRecos, goodUriContext) <- updatedKeepsF
    } yield {
      if (!sentLibs && keepRecos.size < defaultLimit) {
        Ok(Json.obj("recos" -> (keepRecos ++ libs), "uctx" -> goodUriContext, "lctx" -> "sentLibs"))
      } else {
        val lctx = libContext.getOrElse("")
        Ok(Json.obj("recos" -> keepRecos, "uctx" -> goodUriContext, "lctx" -> lctx))
      }
    }
  }

  def keepUpdates(limit: Int, beforeId: Option[String], afterId: Option[String]) = UserAction.async { request =>
    val beforeExtId = beforeId.flatMap(id => ExternalId.asOpt[Keep](id))
    val afterExtId = afterId.flatMap(id => ExternalId.asOpt[Keep](id))

    keepsCommander.getKeepStream(request.userId, limit, beforeExtId, afterExtId).map { updatedKeeps =>
      Ok(Json.obj("updatedKeeps" -> updatedKeeps))
    }
  }

  private def getRecommendationSource(request: UserRequest[_]): RecommendationSource = {
    val agent = UserAgent(request)
    if (agent.isKifiAndroidApp) RecommendationSource.Android
    else if (agent.isKifiIphoneApp) RecommendationSource.IOS
    else throw new IllegalArgumentException(s"the user agent is not of a kifi application: $agent")
  }

}

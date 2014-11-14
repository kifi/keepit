package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.{ ShoeboxServiceController, UserActions, UserActionsHelper }
import com.keepit.commanders.URISummaryCommander
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.model.{ ImageType, NormalizedURIRepo, URISummaryRequest, NormalizedURI }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.store.ImageSize
import scala.concurrent.Future

class URISummaryController @Inject() (
    val userActionsHelper: UserActionsHelper,
    uriSummaryCommander: URISummaryCommander,
    normalizedUriRepo: NormalizedURIRepo,
    db: Database) extends ShoeboxServiceController {

  def updateUriScreenshotsForUriId(id: Id[NormalizedURI]) = Action { request =>
    val nUri = db.readOnlyMaster { implicit session => normalizedUriRepo.get(id) }
    uriSummaryCommander.updateScreenshots(nUri)
    Status(202)("0")
  }

  def getUriImageForUriId(id: Id[NormalizedURI]) = Action.async { request =>
    val nUri = db.readOnlyMaster { implicit session => normalizedUriRepo.get(id) }
    val urlFut = uriSummaryCommander.getURIImage(nUri)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }

  def getUriSummary() = Action.async(parse.tolerantJson) { request =>
    val urlFut = uriSummaryCommander.getURISummaryForRequest(Json.fromJson[URISummaryRequest](request.body).get)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }

  def getUriSummaries() = Action.async(parse.tolerantJson) { request =>
    val uriIdsJson = (request.body \ "uriIds")
    val withDescription = (request.body \ "withDescription").asOpt[Boolean].getOrElse(true)
    val waiting = (request.body \ "waiting").asOpt[Boolean].getOrElse(false)
    val silent = (request.body \ "silent").asOpt[Boolean].getOrElse(false)
    val uriIds = Json.fromJson[Seq[Id[NormalizedURI]]](uriIdsJson).get
    val nUris = db.readOnlyReplica { implicit session =>
      uriIds map (normalizedUriRepo.get(_))
    }
    val uriSummariesFut = if (withDescription && !silent) {
      Future.sequence(nUris map (uriSummaryCommander.getDefaultURISummary(_, waiting)))
    } else {
      Future.sequence(nUris map { nUri =>
        val uriSummaryRequest = URISummaryRequest(nUri.id.get, ImageType.ANY, ImageSize(0, 0), withDescription, waiting, silent)
        uriSummaryCommander.getURISummaryForRequest(uriSummaryRequest, nUri)
      })
    }
    uriSummariesFut map { uriSummaries => Ok(Json.toJson(uriSummaries)) }
  }
}

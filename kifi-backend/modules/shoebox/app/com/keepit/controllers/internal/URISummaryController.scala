package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator}
import com.keepit.commanders.URISummaryCommander
import play.api.mvc.Action
import play.api.libs.json.Json
import com.keepit.model.{URISummaryRequest, NormalizedURI}
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class URISummaryController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  uriSummaryCommander: URISummaryCommander) extends ShoeboxServiceController {

  def updateURIScreenshots() = Action(parse.tolerantJson) { request =>
    val normalizedUri = Json.fromJson[NormalizedURI](request.body).get
    uriSummaryCommander.updateScreenshots(normalizedUri)
    Status(202)("0")
  }

  def getURIImage() = Action.async(parse.tolerantJson) { request =>
    val urlFut = uriSummaryCommander.getURIImage(Json.fromJson[NormalizedURI](request.body).get)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }

  def getURISummary() = Action.async(parse.tolerantJson) { request =>
    val urlFut = uriSummaryCommander.getURISummaryForRequest(Json.fromJson[URISummaryRequest](request.body).get)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }
}

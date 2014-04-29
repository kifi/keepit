package com.keepit.controllers.internal

import com.google.inject.Inject
import com.keepit.common.controller.{ShoeboxServiceController, ActionAuthenticator}
import com.keepit.commanders.URISummaryCommander
import com.keepit.controllers.RequestSource
import play.api.mvc.Action
import play.api.libs.json.Json
import com.keepit.model.NormalizedURI
import play.api.libs.concurrent.Execution.Implicits.defaultContext

class URISummaryController @Inject() (
  actionAuthenticator: ActionAuthenticator,
  uriSummaryCommander: URISummaryCommander) extends ShoeboxServiceController {

  def updateURIScreenshots() = Action.async(parse.tolerantJson) { request =>
    val normalizedUri = Json.fromJson[NormalizedURI](request.body).get
    uriSummaryCommander.updateScreenshots(normalizedUri) map { result => Ok(Json.toJson(result)) }
  }

  def getURIImage() = Action.async(parse.tolerantJson) { request =>
    val urlFut = uriSummaryCommander.getURIImage(Json.fromJson[NormalizedURI](request.body).get, RequestSource.INTERNAL)
    urlFut map { urlOpt => Ok(Json.toJson(urlOpt)) }
  }
}

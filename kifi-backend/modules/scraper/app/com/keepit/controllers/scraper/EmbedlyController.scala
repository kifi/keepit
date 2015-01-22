package com.keepit.controllers.scraper

import com.keepit.common.controller.ScraperServiceController
import com.keepit.scraper.embedly.EmbedlyClient
import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI

class EmbedlyController @Inject() (
  embedly: EmbedlyClient)
    extends ScraperServiceController {

  def getImbedlyInfo() = Action.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    val infoFut = embedly.getEmbedlyInfo(url)
    infoFut.map { infoOpt =>
      Ok(Json.toJson(infoOpt))
    }
  }
}

package com.keepit.controllers.scraper

import com.google.inject.Inject
import com.keepit.commanders.ScraperURISummaryCommander
import com.keepit.common.controller.ScraperServiceController
import com.keepit.scraper.embedly.EmbedlyClient
import com.google.inject.Inject
import play.api.mvc.Action
import play.api.libs.json._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.db.Id
import com.keepit.model.NormalizedURI
import com.keepit.common.store.ImageSize

class URISummaryController @Inject()(
  cmdr: ScraperURISummaryCommander
) extends ScraperServiceController {

  def getURISummaryFromEmbedly() = Action.async(parse.tolerantJson){ request =>
    val js = request.body
    val uri = (js \ "uri").as[NormalizedURI]
    val minSize = (js \ "minSize").as[ImageSize]
    val descOnly = (js \ "descriptionOnly").as[Boolean]
    cmdr.fetchFromEmbedly(uri, minSize, descOnly).map{ res =>
      Ok(Json.toJson(res))
    }
  }
}

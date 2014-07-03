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
import com.keepit.commanders.WordCountCommander

class URISummaryController @Inject()(
  summaryCmdr: ScraperURISummaryCommander,
  wordCountCmdr: WordCountCommander
) extends ScraperServiceController {

  def getURISummaryFromEmbedly() = Action.async(parse.tolerantJson){ request =>
    val js = request.body
    val uri = (js \ "uri").as[NormalizedURI]
    val minSize = (js \ "minSize").as[ImageSize]
    val descOnly = (js \ "descriptionOnly").as[Boolean]
    summaryCmdr.fetchFromEmbedly(uri, minSize, descOnly).map{ res =>
      Ok(Json.toJson(res))
    }
  }

  def getURIWordCount() = Action.async(parse.tolerantJson) { request =>
    val js = request.body
    val uriId = (js \ "uriId").as[Id[NormalizedURI]]
    val url = (js \ "url").as[String]
    wordCountCmdr.getWordCount(uriId, url) map { cnt => Ok(Json.toJson(cnt)) }
  }
}

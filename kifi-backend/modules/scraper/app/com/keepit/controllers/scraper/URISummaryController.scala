package com.keepit.controllers.scraper

import com.google.inject.Inject
import com.keepit.commanders.{ ScraperURISummaryCommander, WordCountCommander }
import com.keepit.common.controller.ScraperServiceController
import com.keepit.common.db.Id
import com.keepit.common.store.ImageSize
import com.keepit.model.NormalizedURI
import com.keepit.scraper.NormalizedURIRef
import com.kifi.macros.json
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json._
import play.api.mvc.Action

class URISummaryController @Inject() (
    summaryCmdr: ScraperURISummaryCommander,
    wordCountCmdr: WordCountCommander) extends ScraperServiceController {

  def getURISummaryFromEmbedly() = Action.async(parse.tolerantJson) { request =>
    val js = request.body
    val uri = (js \ "uri").as[NormalizedURIRef]
    summaryCmdr.fetchFromEmbedly(uri).map { res =>
      Ok(Json.toJson(res))
    }
  }

  def getURIWordCount() = Action.async(parse.tolerantJson) { request =>
    val js = request.body
    val uriId = (js \ "uriId").as[Id[NormalizedURI]]
    val url = (js \ "url").as[String]
    wordCountCmdr.getWordCount(uriId, url) map { cnt => Ok(Json.toJson(cnt)) }
  }

  def fetchAndPersistURIPreview() = Action.async(parse.tolerantJson) { request =>
    val url = (request.body \ "url").as[String]
    summaryCmdr.fetchAndPersistURIPreview(url) map { res => Ok(Json.toJson(res)) }
  }
}

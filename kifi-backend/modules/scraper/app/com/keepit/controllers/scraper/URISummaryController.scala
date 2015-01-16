package com.keepit.controllers.scraper

import com.google.inject.Inject
import com.keepit.commanders.{ NormalizedURIRef, ScraperURISummaryCommander, WordCountCommander }
import com.keepit.common.controller.ScraperServiceController
import com.keepit.common.db.Id
import com.keepit.common.store.ImageSize
import com.keepit.model.NormalizedURI
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
    val descOnly = (js \ "descriptionOnly").as[Boolean]
    summaryCmdr.fetchFromEmbedly(uri, descOnly).map { res =>
      Ok(Json.toJson(res))
    }
  }

  def getURIWordCount() = Action.async(parse.tolerantJson) { request =>
    val js = request.body
    val uriId = (js \ "uriId").as[Id[NormalizedURI]]
    val url = (js \ "url").asOpt[String]
    wordCountCmdr.getWordCount(uriId, url) map { cnt => Ok(Json.toJson(cnt)) }
  }
}

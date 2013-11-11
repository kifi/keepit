package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.controller.{WebsiteController, ScraperServiceController, ActionAuthenticator}
import com.keepit.model._
import play.api.mvc.Action
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json._
import com.keepit.scraper.extractor.ExtractorFactory
import com.keepit.common.logging.Logging
import com.keepit.search.ArticleStore
import com.keepit.model.ScrapeInfo
import com.keepit.shoebox.ShoeboxServiceClient
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ScreenshotStore

class ScraperController @Inject() (
  airbrake: AirbrakeNotifier,
  actionAuthenticator:ActionAuthenticator,
  scrapeProcessor: ScrapeProcessorPlugin
) extends WebsiteController(actionAuthenticator) with ScraperServiceController with Logging {

  def getBasicArticle(url:String) = Action { request =>
    val resF = scrapeProcessor.fetchBasicArticle(url)
    Async {
      resF.map { res =>
        val json = Json.toJson(res)
        log.info(s"[getBasicArticle($url)] result: ${json}")
        Ok(json)
      }
    }
  }

  def asyncScrapeWithInfo() = Action(parse.json) { request =>
    val jsValues = request.body.as[JsArray].value
    require(jsValues != null && jsValues.length == 2, "Expect args to be nUri & scrapeInfo")
    val uri = jsValues(0).as[NormalizedURI]
    val info = jsValues(1).as[ScrapeInfo]
    log.info(s"[asyncScrapeWithInfo] url=${uri.url}, scrapeInfo=$info")
    val tupF = scrapeProcessor.scrapeArticle(uri, info)
    Async {
      tupF.map { t =>
        val res = ScrapeTuple(t._1, t._2)
        log.info(s"[asyncScrapeWithInfo(${uri.url})] result=${t._1}")
        Ok(Json.toJson(res))
      }
    }
  }

  def scheduleScrape() = Action(parse.json) { request =>
    val jsValues = request.body.as[JsArray].value
    require(jsValues != null && jsValues.length == 2, "Expect args to be nUri & scrapeInfo")
    val normalizedUri = jsValues(0).as[NormalizedURI]
    val info = jsValues(1).as[ScrapeInfo]
    scrapeProcessor.asyncScrape(normalizedUri, info)
    log.info(s"[scheduleScrape] scheduled scraping job for (url=${normalizedUri.url}, info=$info)")
    Ok(JsBoolean(true))
  }

}
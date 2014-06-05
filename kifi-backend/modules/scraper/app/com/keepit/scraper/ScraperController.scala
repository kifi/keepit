package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.controller.{ScraperServiceController, ActionAuthenticator}
import com.keepit.model._
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.scraper.extractor.ExtractorProviderTypes
import com.keepit.common.logging.Logging
import com.keepit.model.ScrapeInfo
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.performance.timing
import com.keepit.common.net.URI

class ScraperController @Inject() (
  airbrake: AirbrakeNotifier,
  actionAuthenticator:ActionAuthenticator,
  scrapeProcessor: ScrapeProcessor
) extends ScraperServiceController with Logging {

  def getBasicArticle() = Action.async(parse.json) { request =>
    log.info(s"getBasicArticle body=${request.body}")
    processBasicArticleRequest(request.body).map{ articleOption =>
      val json = Json.toJson(articleOption)
      val url = (request.body \ "url").as[String]
      log.info(s"[getBasicArticle($url})] result: $json")
      Ok(json)
    }(ExecutionContext.fj)
  }

  def getSignature() = Action.async(parse.json) { request =>
    log.info(s"getSignature body=${request.body}")
    processBasicArticleRequest(request.body).map { articleOption =>
      val url = (request.body \ "url").as[String]
      val signatureOption = articleOption.collect { case article if article.destinationUrl == url => article.signature }
      val json = Json.toJson(signatureOption.map(_.toBase64()))
      log.info(s"[getSignature($url)] result: $json")
      Ok(json)
    }(ExecutionContext.fj)
  }

  private def processBasicArticleRequest(parameters: JsValue): Future[Option[BasicArticle]] = {
    val url = (parameters \ "url").as[String]
    URI.parse(url).get.host match {
      case None =>
        throw new IllegalArgumentException(s"url $url has no host!")
      case Some(_) =>
        val proxyOpt = (parameters \ "proxy").asOpt[HttpProxy]
        val extractorProviderTypeOpt = (parameters \ "extractorProviderType").asOpt[String] flatMap { s => ExtractorProviderTypes.ALL.find(_.name == s) }
        timing(s"fetchBasicArticle($url,$proxyOpt,$extractorProviderTypeOpt)") {
          scrapeProcessor.fetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)
        }
    }
  }

  def asyncScrapeWithInfo() = Action.async(parse.json) { request =>
    val jsValues = request.body.as[JsArray].value
    require(jsValues != null && jsValues.length >= 2, "Expect args to be nUri & scrapeInfo")
    val uri = jsValues(0).as[NormalizedURI]
    val info = jsValues(1).as[ScrapeInfo]
    log.info(s"[asyncScrapeWithInfo] url=${uri.url}, scrapeInfo=$info")
    val tupF = scrapeProcessor.scrapeArticle(uri, info, None)
    tupF.map { t =>
      val res = ScrapeTuple(t._1, t._2)
      log.info(s"[asyncScrapeWithInfo(${uri.url})] result=${t._1}")
      Ok(Json.toJson(res))
    }(ExecutionContext.fj)
  }

  def asyncScrapeWithRequest() = Action.async(parse.json) { request =>
    val scrapeRequest = request.body.as[ScrapeRequest]
    log.info(s"[asyncScrapeWithRequest] req=$scrapeRequest")
    val tupF = scrapeProcessor.scrapeArticle(scrapeRequest.uri, scrapeRequest.scrapeInfo, scrapeRequest.proxyOpt)
    tupF.map { t =>
      val res = ScrapeTuple(t._1, t._2)
      log.info(s"[asyncScrapeWithInfo(${scrapeRequest.uri.url})] result=${t._1}")
      Ok(Json.toJson(res))
    }(ExecutionContext.fj)
  }
}

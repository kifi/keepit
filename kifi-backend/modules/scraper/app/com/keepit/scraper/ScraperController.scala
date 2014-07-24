package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.controller.{ ScraperServiceController, ActionAuthenticator }
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
    actionAuthenticator: ActionAuthenticator,
    scrapeProcessor: ScrapeProcessor) extends ScraperServiceController with Logging {

  def getBasicArticle() = Action.async(parse.json) { request =>
    println(s"getBasicArticle body=${request.body}")
    processBasicArticleRequest(request.body).map { articleOption =>
      val json = Json.toJson(articleOption)
      val url = (request.body \ "url").as[String]
      println(s"[getBasicArticle($url})] result: $json")
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
}

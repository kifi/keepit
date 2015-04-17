package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.controller.{ ScraperServiceController, UserActionsHelper }
import com.keepit.model._
import play.api.mvc.Action
import play.api.libs.json._
import com.keepit.scraper.extractor.ExtractorProviderTypes
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import scala.concurrent.Future
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.performance.timing
import com.keepit.common.net.URI

class ScraperController @Inject() (
    airbrake: AirbrakeNotifier,
    val userActionsHelper: UserActionsHelper,
    scrapeProcessor: ScrapeProcessor) extends ScraperServiceController with Logging {

  implicit val fj = ExecutionContext.fj

  def getBasicArticle() = Action.async(parse.json) { request =>
    log.info(s"getBasicArticle body=${request.body}")
    processBasicArticleRequest(request.body).map { articleOption =>
      val json = Json.toJson(articleOption)
      val url = (request.body \ "url").as[String]
      log.info(s"[getBasicArticle($url})] result: $json")
      Ok(json)
    }
  }

  def getSignature() = Action.async(parse.json) { request =>
    log.info(s"getSignature body=${request.body}")
    processBasicArticleRequest(request.body) recover { case _: java.util.concurrent.TimeoutException => None } map { articleOption =>
      val url = (request.body \ "url").as[String]
      val signatureOption = articleOption.collect { case article if article.destinationUrl == url => article.signature }
      val json = Json.toJson(signatureOption.map(_.toBase64()))
      log.info(s"[getSignature($url)] result: $json")
      Ok(json)
    }
  }

  def status() = Action.async { request =>
    scrapeProcessor.status.map { res =>
      val json = Json.toJson(res)
      log.info(s"[getStatus] result: $json")
      Ok(json)
    }
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

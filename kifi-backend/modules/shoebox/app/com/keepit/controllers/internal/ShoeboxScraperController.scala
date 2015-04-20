package com.keepit.controllers.internal

import com.google.inject.{ Singleton, Provider, Inject }
import com.keepit.common.controller.ShoeboxServiceController
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ URI, URIParser }
import com.keepit.common.performance._
import com.keepit.common.service.FortyTwoServices
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.normalizer._
import com.keepit.rover.document.utils.Signature
import com.keepit.rover.fetcher.HttpRedirect
import play.api.mvc.Action
import play.api.libs.json.{ JsObject, JsArray, JsBoolean, Json }
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class ShoeboxScraperController @Inject() (
  urlPatternRules: UrlPatternRulesCommander,
  db: Database,
  imageInfoRepo: ImageInfoRepo,
  normUriRepo: NormalizedURIRepo,
  airbrake: AirbrakeNotifier,
  pageInfoRepo: PageInfoRepo,
  scrapeInfoRepo: ScrapeInfoRepo,
  scraperHelper: ScraperCallbackHelper)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getAllURLPatternRules() = Action { request =>
    val patterns = urlPatternRules.rules().rules
    Ok(Json.toJson(patterns))
  }

  def updateNormalizedURI(uriId: Id[NormalizedURI]) = SafeAsyncAction(parse.tolerantJson) { request =>
    val saveResult = Try {
      // Handle serialization in session to be transactional.
      val originalNormalizedUri = db.readOnlyMaster { implicit s => normUriRepo.get(uriId) }
      val originalJson = Json.toJson(originalNormalizedUri).as[JsObject]
      val newNormalizedUriResult = Json.fromJson[NormalizedURI](originalJson ++ request.body.as[JsObject])

      newNormalizedUriResult.fold({ invalid =>
        val error = "Could not deserialize NormalizedURI ($uriId) update: $invalid\nOriginal: $originalNormalizedUri\nbody: ${request.body}"
        airbrake.notify(error)
        throw new Exception(error)
      }, { normalizedUri =>
        scraperHelper.saveNormalizedURI(normalizedUri)
      })
    }
    saveResult match {
      case Success(res) =>
        Ok
      case Failure(ex) =>
        log.error(s"Could not deserialize NormalizedURI ($uriId) update: $ex\nbody: ${request.body}")
        airbrake.notify(s"Could not deserialize NormalizedURI ($uriId) update", ex)
        throw ex
    }
  }

  def getProxy(url: String) = SafeAsyncAction { request =>
    val httpProxyOpt = db.readOnlyReplica(2) { implicit session =>
      urlPatternRules.getProxy(url)
    }
    log.debug(s"[getProxy($url): result=$httpProxyOpt")
    Ok(Json.toJson(httpProxyOpt))
  }

  def getProxyP = SafeAsyncAction(parse.tolerantJson) { request =>
    val url = request.body.as[String]
    val httpProxyOpt = db.readOnlyReplica(2) { implicit session =>
      urlPatternRules.getProxy(url)
    }
    Ok(Json.toJson(httpProxyOpt))
  }

  def assignScrapeTasks(zkId: Id[ScraperWorker], max: Int) = Action.async { request =>
    scraperHelper.assignTasks(zkId, max) map { res =>
      Ok(Json.toJson(res))
    }
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    statsd.gauge("saveScrapeInfo", 1)
    val ts = System.currentTimeMillis
    val json = request.body
    val info = json.as[ScrapeInfo]
    val saved = scraperHelper.assignLock.withLock {
      db.readWrite(attempts = 3) { implicit s =>
        val nuri = normUriRepo.get(info.uriId)
        if (URI.parse(nuri.url).isFailure) {
          scrapeInfoRepo.save(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
          airbrake.notify(s"can't parse $nuri, not passing it to the scraper, marking as unscrapable")
        } else {
          scrapeInfoRepo.save(info)
        }
      }
    }
    log.info(s"[saveScrapeInfo] time-lapsed:${System.currentTimeMillis - ts} result=$saved")
    Ok
  }

}

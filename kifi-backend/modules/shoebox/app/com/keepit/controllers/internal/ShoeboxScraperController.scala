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
  normUriRepo: NormalizedURIRepo,
  airbrake: AirbrakeNotifier)(implicit private val clock: Clock,
    private val fortyTwoServices: FortyTwoServices)
    extends ShoeboxServiceController with Logging {

  val MaxContentLength = 6000

  def getAllURLPatternRules() = Action { request =>
    val patterns = urlPatternRules.rules().rules
    Ok(Json.toJson(patterns))
  }

  def updateNormalizedURI(uriId: Id[NormalizedURI]) = SafeAsyncAction(parse.tolerantJson) { request =>
    throw new UnsupportedOperationException
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
    throw new UnsupportedOperationException
  }

  def saveScrapeInfo() = SafeAsyncAction(parse.tolerantJson) { request =>
    throw new UnsupportedOperationException
  }

}

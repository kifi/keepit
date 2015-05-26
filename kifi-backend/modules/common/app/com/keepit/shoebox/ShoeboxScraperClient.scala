package com.keepit.shoebox

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.db.{ SequenceNumber, ExternalId, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.ThrottledServiceClient
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import org.joda.time.DateTime
import play.api.libs.json.{ JsString, JsArray, Json }
import play.api.libs.json.Json.JsValueWrapper
import com.keepit.common.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.concurrent.ReactiveLock

import scala.concurrent.Future
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

trait ShoeboxScraperClient extends ThrottledServiceClient {
  private val ? = null
  def getAllURLPatterns(): Future[UrlPatternRules]
  def getProxy(url: String): Future[Option[HttpProxy]]
  def getProxyP(url: String): Future[Option[HttpProxy]]
}

@Singleton
class ShoeboxScraperClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  urlPatternRuleAllCache: UrlPatternRulesAllCache)
    extends ShoeboxScraperClient with Logging {

  val MaxUrlLength = 3000
  val longTimeout = CallTimeouts(responseTimeout = Some(60000), maxWaitTime = Some(60000), maxJsonParseTime = Some(30000))
  override val limiter = new ReactiveLock(8, Some(32))
  val assignScrapeTasksLimiter = new ReactiveLock(1, Some(5)) //in fact we should have at most one in the queue

  def getAllURLPatterns(): Future[UrlPatternRules] = {
    urlPatternRuleAllCache.getOrElseFuture(UrlPatternRulesAllKey()) {
      call(Shoebox.internal.allURLPatternRules(), routingStrategy = offlinePriority).map { r =>
        Json.fromJson[UrlPatternRules](r.json).get
      }
    }
  }

  def getProxy(url: String): Future[Option[HttpProxy]] = limiter.withLockFuture {
    call(Shoebox.internal.getProxy(url), routingStrategy = offlinePriority).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getProxyP(url: String): Future[Option[HttpProxy]] = limiter.withLockFuture {
    call(Shoebox.internal.getProxyP, Json.toJson(url), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }
}

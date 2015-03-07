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
import com.keepit.rover.fetcher.HttpRedirect
import com.keepit.scraper.{ Signature, ScrapeRequest }
import org.joda.time.DateTime
import play.api.libs.json.{ JsString, JsArray, Json }
import play.api.libs.json.Json.JsValueWrapper
import com.keepit.common.core._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

trait ShoeboxScraperClient extends ThrottledServiceClient {
  private val ? = null
  def getAllURLPatterns(): Future[UrlPatternRules]
  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]]
  def saveScrapeInfo(info: ScrapeInfo): Future[Unit]
  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI]
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit]
  def updateNormalizedURI(uriId: => Id[NormalizedURI], createdAt: => DateTime = ?, updatedAt: => DateTime = ?, externalId: => ExternalId[NormalizedURI] = ?, title: => Option[String] = ?, url: => String = ?, urlHash: => UrlHash = UrlHash(?), state: => State[NormalizedURI] = ?, seq: => SequenceNumber[NormalizedURI] = SequenceNumber(-1), screenshotUpdatedAt: => Option[DateTime] = ?, restriction: => Option[Restriction] = ?, normalization: => Option[Normalization] = ?, redirect: => Option[Id[NormalizedURI]] = ?, redirectTime: => Option[DateTime] = ?): Future[Unit]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]
  def getProxy(url: String): Future[Option[HttpProxy]]
  def getProxyP(url: String): Future[Option[HttpProxy]]
  def getLatestKeep(url: String): Future[Option[Keep]]
  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]]
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

  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]] = {
    statsd.gauge("getUriImage", 1)
    call(Shoebox.internal.getUriImage(nUriId), routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Option[String]](r.json).get
    }
  }

  def getAllURLPatterns(): Future[UrlPatternRules] = {
    urlPatternRuleAllCache.getOrElseFuture(UrlPatternRulesAllKey()) {
      call(Shoebox.internal.allURLPatternRules(), routingStrategy = offlinePriority).map { r =>
        Json.fromJson[UrlPatternRules](r.json).get
      }
    }
  }

  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = {
    statsd.gauge("assignScrapeTasks", 1)
    call(Shoebox.internal.assignScrapeTasks(zkId, max), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[Seq[ScrapeRequest]]
    }
  }

  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = {
    statsd.gauge(s"saveScrapeInfo.${info.state}", 1)
    call(Shoebox.internal.saveScrapeInfo(), Json.toJson(info), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r => Unit }
  }

  @deprecated("Dangerous call. Use updateNormalizedURI instead.", "2014-01-30")
  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI] = {
    statsd.gauge("saveNormalizedURI", 1)
    call(Shoebox.internal.saveNormalizedURI(), Json.toJson(uri), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = {
    statsd.gauge("updateNormalizedURIState", 1)
    val json = Json.obj("state" -> state)
    call(Shoebox.internal.updateNormalizedURI(uriId), json, callTimeouts = longTimeout, routingStrategy = offlinePriority).imap(_ => {})
  }

  def updateNormalizedURI(uriId: => Id[NormalizedURI],
    createdAt: => DateTime,
    updatedAt: => DateTime,
    externalId: => ExternalId[NormalizedURI],
    title: => Option[String],
    url: => String,
    urlHash: => UrlHash,
    state: => State[NormalizedURI],
    seq: => SequenceNumber[NormalizedURI],
    screenshotUpdatedAt: => Option[DateTime],
    restriction: => Option[Restriction],
    normalization: => Option[Normalization],
    redirect: => Option[Id[NormalizedURI]],
    redirectTime: => Option[DateTime]): Future[Unit] = {
    import com.keepit.common.strings.OptionWrappedJsObject
    val safeUrlHash = Option(urlHash).map(p => Option(p.hash)).flatten
    val safeSeq = Option(seq).map(v => if (v.value == -1L) None else Some(v)).flatten

    val safeJsonParams: Seq[(String, JsValueWrapper)] = Seq(
      "createdAt" -> Option(createdAt),
      "updatedAt" -> Option(updatedAt),
      "externalId" -> Option(externalId),
      "title" -> Option(title),
      "url" -> Option(url),
      "urlHash" -> safeUrlHash,
      "state" -> Option(state),
      "seq" -> safeSeq,
      "screenshotUpdatedAt" -> Option(screenshotUpdatedAt),
      "restriction" -> Option(restriction),
      "normalization" -> Option(normalization),
      "redirect" -> Option(redirect),
      "redirectTime" -> Option(redirectTime)
    )
    val payload = Json.obj(safeJsonParams: _*)
    val stripped = payload.stripJsNulls()
    call(Shoebox.internal.updateNormalizedURI(uriId), stripped, callTimeouts = longTimeout, routingStrategy = offlinePriority).imap(_ => {})
  }

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = {
    statsd.gauge("recordPermanentRedirect", 1)
    call(Shoebox.internal.recordPermanentRedirect(), JsArray(Seq(Json.toJson[NormalizedURI](uri), Json.toJson[HttpRedirect](redirect))), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    statsd.gauge("recordScrapedNormalization", 1)
    val payload = Json.obj(
      "id" -> uriId.id,
      "signature" -> uriSignature.toBase64(),
      "url" -> candidateUrl,
      "normalization" -> candidateNormalization,
      "alternateUrls" -> alternateUrls
    )
    call(Shoebox.internal.recordScrapedNormalization(), payload, callTimeouts = longTimeout, routingStrategy = offlinePriority).imap(_ => {})
  }

  def getProxy(url: String): Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxy(url), routingStrategy = offlinePriority).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getProxyP(url: String): Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxyP, Json.toJson(url), callTimeouts = longTimeout, routingStrategy = offlinePriority).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getLatestKeep(url: String): Future[Option[Keep]] = {
    statsd.gauge("getLatestKeep", 1)
    call(Shoebox.internal.getLatestKeep(), callTimeouts = longTimeout, body = JsString(url), routingStrategy = offlinePriority).map { r =>
      Json.fromJson[Option[Keep]](r.json).get
    }
  }

}

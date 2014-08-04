package com.keepit.shoebox

import com.google.inject.Inject
import com.keepit.common.db.{ SequenceNumber, ExternalId, State, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Shoebox
import com.keepit.common.service.ServiceClient
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.scraper.{ Signature, HttpRedirect, ScrapeRequest }
import org.joda.time.DateTime
import play.api.libs.json.{ JsString, JsArray, Json }
import play.api.libs.json.Json.JsValueWrapper
import com.keepit.common._
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future
import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

trait ShoeboxScraperClient extends ServiceClient {
  private val ? = null
  def getAllURLPatterns(): Future[Seq[UrlPatternRule]]
  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]]
  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean]
  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean]
  def saveScrapeInfo(info: ScrapeInfo): Future[Unit]
  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI]
  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit]
  def savePageInfo(pageInfo: PageInfo): Future[Unit]
  def getImageInfo(id: Id[ImageInfo]): Future[ImageInfo]
  def saveImageInfo(imgInfo: ImageInfo): Future[ImageInfo]
  def updateNormalizedURI(uriId: => Id[NormalizedURI], createdAt: => DateTime = ?, updatedAt: => DateTime = ?, externalId: => ExternalId[NormalizedURI] = ?, title: => Option[String] = ?, url: => String = ?, urlHash: => UrlHash = UrlHash(?), state: => State[NormalizedURI] = ?, seq: => SequenceNumber[NormalizedURI] = SequenceNumber(-1), screenshotUpdatedAt: => Option[DateTime] = ?, restriction: => Option[Restriction] = ?, normalization: => Option[Normalization] = ?, redirect: => Option[Id[NormalizedURI]] = ?, redirectTime: => Option[DateTime] = ?): Future[Unit]
  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI]
  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit]
  def getProxy(url: String): Future[Option[HttpProxy]]
  def getProxyP(url: String): Future[Option[HttpProxy]]
  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]]
  def getLatestKeep(url: String): Future[Option[Keep]]
  def updateScreenshots(nUriId: Id[NormalizedURI]): Future[Unit]
  def saveBookmark(bookmark: Keep): Future[Keep]
  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]]
}

class ShoeboxScraperClientImpl @Inject() (
  override val serviceCluster: ServiceCluster,
  override val httpClient: HttpClient,
  val airbrakeNotifier: AirbrakeNotifier,
  urlPatternRuleAllCache: UrlPatternRuleAllCache)
    extends ShoeboxScraperClient with Logging {

  val MaxUrlLength = 3000
  val longTimeout = CallTimeouts(responseTimeout = Some(60000), maxWaitTime = Some(60000), maxJsonParseTime = Some(30000))

  def getUriImage(nUriId: Id[NormalizedURI]): Future[Option[String]] = {
    call(Shoebox.internal.getUriImage(nUriId)).map { r =>
      Json.fromJson[Option[String]](r.json).get
    }
  }

  def updateScreenshots(nUriId: Id[NormalizedURI]): Future[Unit] = {
    call(Shoebox.internal.updateScreenshots(nUriId)).map { r => assert(r.status == 202); () }
  }

  def getAllURLPatterns(): Future[Seq[UrlPatternRule]] = {
    urlPatternRuleAllCache.getOrElseFuture(UrlPatternRuleAllKey()) {
      call(Shoebox.internal.allURLPatternRules()).map { r =>
        Json.fromJson[Seq[UrlPatternRule]](r.json).get
      }
    }
  }

  def assignScrapeTasks(zkId: Long, max: Int): Future[Seq[ScrapeRequest]] = {
    call(Shoebox.internal.assignScrapeTasks(zkId, max), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[Seq[ScrapeRequest]]
    }
  }

  def saveScrapeInfo(info: ScrapeInfo): Future[Unit] = {
    call(Shoebox.internal.saveScrapeInfo(), Json.toJson(info), callTimeouts = longTimeout).map { r => Unit }
  }

  def savePageInfo(pageInfo: PageInfo): Future[Unit] = {
    call(Shoebox.internal.savePageInfo(), Json.toJson(pageInfo), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r => Unit }
  }

  def getImageInfo(id: Id[ImageInfo]): Future[ImageInfo] = {
    call(Shoebox.internal.getImageInfo(id)).map { r =>
      r.json.as[ImageInfo]
    }
  }

  def saveImageInfo(imgInfo: ImageInfo): Future[ImageInfo] = {
    call(Shoebox.internal.saveImageInfo(), Json.toJson(imgInfo), callTimeouts = longTimeout, routingStrategy = leaderPriority).map { r =>
      r.json.as[ImageInfo]
    }
  }

  @deprecated("Dangerous call. Use updateNormalizedURI instead.", "2014-01-30")
  def saveNormalizedURI(uri: NormalizedURI): Future[NormalizedURI] = {
    call(Shoebox.internal.saveNormalizedURI(), Json.toJson(uri), callTimeouts = longTimeout).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def updateNormalizedURIState(uriId: Id[NormalizedURI], state: State[NormalizedURI]): Future[Unit] = {
    val json = Json.obj("state" -> state)
    call(Shoebox.internal.updateNormalizedURI(uriId), json, callTimeouts = longTimeout).imap(_ => {})
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
    call(Shoebox.internal.updateNormalizedURI(uriId), stripped, callTimeouts = longTimeout, routingStrategy = leaderPriority).imap(_ => {})
  }

  def recordPermanentRedirect(uri: NormalizedURI, redirect: HttpRedirect): Future[NormalizedURI] = {
    call(Shoebox.internal.recordPermanentRedirect(), JsArray(Seq(Json.toJson[NormalizedURI](uri), Json.toJson[HttpRedirect](redirect))), callTimeouts = longTimeout).map { r =>
      r.json.as[NormalizedURI]
    }
  }

  def recordScrapedNormalization(uriId: Id[NormalizedURI], uriSignature: Signature, candidateUrl: String, candidateNormalization: Normalization, alternateUrls: Set[String]): Future[Unit] = {
    val payload = Json.obj(
      "id" -> uriId.id,
      "signature" -> uriSignature.toBase64(),
      "url" -> candidateUrl,
      "normalization" -> candidateNormalization,
      "alternateUrls" -> alternateUrls
    )
    call(Shoebox.internal.recordScrapedNormalization(), payload, callTimeouts = longTimeout).imap(_ => {})
  }

  def getProxy(url: String): Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxy(url)).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def getProxyP(url: String): Future[Option[HttpProxy]] = {
    call(Shoebox.internal.getProxyP, Json.toJson(url), callTimeouts = longTimeout).map { r =>
      if (r.json == null) None else r.json.asOpt[HttpProxy]
    }
  }

  def isUnscrapable(url: String, destinationUrl: Option[String]): Future[Boolean] = {
    call(Shoebox.internal.isUnscrapable(url.take(MaxUrlLength), destinationUrl.map(_.take(MaxUrlLength)))).map { r =>
      r.json.as[Boolean]
    }
  }

  def isUnscrapableP(url: String, destinationUrl: Option[String]): Future[Boolean] = {
    val destUrl = if (destinationUrl.isDefined && url == destinationUrl.get) {
      log.debug(s"[isUnscrapableP] url==destUrl $url; ignored") // todo: fix calling code
      None
    } else destinationUrl map { dUrl =>
      log.debug(s"[isUnscrapableP] url($url) != destUrl($dUrl)")
      dUrl
    }
    val payload = JsArray(destUrl match {
      case Some(dUrl) => Seq(Json.toJson(url.take(MaxUrlLength)), Json.toJson(dUrl.take(MaxUrlLength)))
      case None => Seq(Json.toJson(url.take(MaxUrlLength)))
    })
    call(Shoebox.internal.isUnscrapableP, payload, callTimeouts = longTimeout).map { r =>
      r.json.as[Boolean]
    }
  }

  def getBookmarksByUriWithoutTitle(uriId: Id[NormalizedURI]): Future[Seq[Keep]] = {
    call(Shoebox.internal.getBookmarksByUriWithoutTitle(uriId), callTimeouts = longTimeout).map { r =>
      r.json.as[JsArray].value.map(js => Json.fromJson[Keep](js).get)
    }
  }

  def getLatestKeep(url: String): Future[Option[Keep]] = {
    call(Shoebox.internal.getLatestKeep(), callTimeouts = longTimeout, body = JsString(url)).map { r =>
      Json.fromJson[Option[Keep]](r.json).get
    }
  }

  def saveBookmark(bookmark: Keep): Future[Keep] = {
    call(Shoebox.internal.saveBookmark(), Json.toJson(bookmark), callTimeouts = longTimeout).map { r =>
      Json.fromJson[Keep](r.json).get
    }
  }

}

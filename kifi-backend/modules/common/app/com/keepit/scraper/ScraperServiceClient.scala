package com.keepit.scraper

//import play.api.libs.concurrent.Execution.Implicits.defaultContext

import java.io.IOException

import com.google.inject.Inject
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.db.{ ExternalId, Id }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.{ CallTimeouts, HttpClient }
import com.keepit.common.routes.Scraper
import com.keepit.common.service.{ RequestConsolidator, ServiceClient, ServiceType }
import com.keepit.common.store.ImageSize
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.search.Article
import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.concurrent.Future
import scala.concurrent.duration._

case class ScrapeTuple(uri: NormalizedURI, articleOpt: Option[Article])
object ScrapeTuple {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'article).formatNullable[Article]
  )(ScrapeTuple.apply _, unlift(ScrapeTuple.unapply))
}

case class ScrapeRequest(uri: NormalizedURI, scrapeInfo: ScrapeInfo, pageInfoOpt: Option[PageInfo], proxyOpt: Option[HttpProxy]) {
  override def toString = s"(${uri.toShortString},${scrapeInfo.toShortString},$pageInfoOpt,$proxyOpt)"
  def toShortString = s"(${uri.id},${scrapeInfo.id},${pageInfoOpt.flatMap(_.id)},${uri.url.take(50)}"
}

object ScrapeRequest {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'scrapeInfo).format[ScrapeInfo] and
    (__ \ 'pageInfo).formatNullable[PageInfo] and
    (__ \ 'proxy).formatNullable[HttpProxy]
  )(ScrapeRequest.apply _, unlift(ScrapeRequest.unapply))
}

case class ScraperTaskType(value: String)
object ScraperTaskType {
  val SCRAPE = ScraperTaskType("scrape")
  val FETCH_BASIC = ScraperTaskType("fetch_basic")
  val SCRAPE_ARTICLE = ScraperTaskType("scrape_article")
  val UNKNOWN = ScraperTaskType("unknown")
  val ALL: Seq[ScraperTaskType] = Seq(SCRAPE, FETCH_BASIC, SCRAPE_ARTICLE)
  implicit val format = new Format[ScraperTaskType] {
    def reads(json: JsValue) = JsSuccess(ALL.find(_.value == json.asOpt[String].getOrElse("")) getOrElse UNKNOWN)
    def writes(taskType: ScraperTaskType) = JsString(taskType.value)
  }
}

case class ScraperTaskDetails(
  name: String,
  url: String,
  submitDateTime: DateTime,
  callDateTime: Option[DateTime],
  taskType: ScraperTaskType,
  uriId: Option[Id[NormalizedURI]],
  scrapeId: Option[Id[ScrapeInfo]],
  killCount: Option[Int],
  extractor: Option[String])
object ScraperTaskDetails {
  implicit val format = (
    (__ \ 'name).format[String] and
    (__ \ 'url).format[String] and
    (__ \ 'submitDateTime).format[DateTime] and
    (__ \ 'callDateTime).formatNullable[DateTime] and
    (__ \ 'taskType).format[ScraperTaskType] and
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'scrapeId).formatNullable(Id.format[ScrapeInfo]) and
    (__ \ 'killCount).formatNullable[Int] and
    (__ \ 'extractor).formatNullable[String]
  )(ScraperTaskDetails.apply _, unlift(ScraperTaskDetails.unapply))
}

case class ScraperThreadDetails(state: Option[String], share: Option[String], description: Either[ScraperTaskDetails, String]) // todo(ray): removeme
object ScraperThreadDetails {
  def buildFromString(details: String): Option[ScraperThreadDetails] = {
    val re = """^(.*?)\s+(\w+)\s+([0-9]*\.?[0-9]*%)\s+share""".r
    re findFirstIn details match {
      case Some(re(task, state, share)) => {
        try Some(ScraperThreadDetails(Some(state), Some(share), Left(Json.parse(task).as[ScraperTaskDetails])))
        catch { case (_: IOException | _: JsResultException) => Some(ScraperThreadDetails(Some(state), Some(share), Right(task))) }
      }
      case None => None
    }
  }
}

case class ScraperThreadInstanceInfo(info: AmazonInstanceInfo, jobInfo: Either[Seq[ScraperThreadDetails], String]) {

  lazy val forkJoinThreadDetails: Seq[ScraperThreadDetails] = jobInfo match {
    case Left(threadDetails) => threadDetails
    case Right(details) =>
      (details.lines.toSeq.filterNot(_.isEmpty) map ScraperThreadDetails.buildFromString).flatten
  }
  lazy val scrapeThreadDetails: Seq[ScraperThreadDetails] = threadDetailsForTaskType(ScraperTaskType.SCRAPE)
  lazy val fetchBasicThreadDetails: Seq[ScraperThreadDetails] = threadDetailsForTaskType(ScraperTaskType.FETCH_BASIC)
  lazy val otherThreadDetails: Seq[ScraperThreadDetails] = forkJoinThreadDetails.filter(_.description.isRight)

  def threadDetailsForTaskType(taskType: ScraperTaskType): Seq[ScraperThreadDetails] = {
    forkJoinThreadDetails.filter { threadDetails =>
      threadDetails.description match {
        case Left(details) => details.taskType == taskType
        case Right(_) => false
      }
    }
  }
}

@json case class NormalizedURIRef(id: Id[NormalizedURI], url: String, externalId: ExternalId[NormalizedURI])

trait ScraperServiceClient extends ServiceClient {
  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  final val serviceType = ServiceType.SCRAPER

  def status(): Seq[Future[(AmazonInstanceInfo, Seq[ScrapeJobStatus])]]
  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[BasicArticle]]
  def getSignature(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[Signature]]
  def getThreadDetails(filterState: Option[String] = None): Seq[Future[ScraperThreadInstanceInfo]]
  def getPornDetectorModel(): Future[Map[String, Float]]
  def detectPorn(query: String): Future[Map[String, Float]]
  def whitelist(words: String): Future[String]
  def getEmbedlyImageInfos(uriId: Id[NormalizedURI], url: String): Future[Seq[ImageInfo]]
  def getURISummaryFromEmbedly(uri: NormalizedURIRef, descriptionOnly: Boolean): Future[Option[URISummary]]
  def getURIWordCount(uriId: Id[NormalizedURI], url: Option[String]): Future[Int]
  def getURIWordCountOpt(uriId: Id[NormalizedURI], url: Option[String]): Option[Int]
}

case class ScraperCacheProvider @Inject() (
  wordCountCache: NormalizedURIWordCountCache)

class ScraperServiceClientImpl @Inject() (
    val airbrakeNotifier: AirbrakeNotifier,
    defaultHttpClient: HttpClient,
    val serviceCluster: ServiceCluster,
    val cacheProvider: ScraperCacheProvider) extends ScraperServiceClient with Logging {

  val longTimeout = CallTimeouts(responseTimeout = Some(60000))
  val superExtraLongTimeoutJustForEmbedly = CallTimeouts(responseTimeout = Some(250000), maxWaitTime = Some(3000), maxJsonParseTime = Some(10000))
  val httpClient = defaultHttpClient.withTimeout(longTimeout)

  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    call(Scraper.internal.getBasicArticle, Json.obj("url" -> url, "proxy" -> Json.toJson(proxy), "extractorProviderType" -> extractorProviderType.map(_.name))).map { r =>
      r.json.validate[BasicArticle].asOpt
    }
  }

  private[this] val consolidateGetSignatureReq = new RequestConsolidator[String, Option[Signature]](5 minutes)

  def getSignature(url: String, proxy: Option[HttpProxy], extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = consolidateGetSignatureReq(url) { url =>
    call(Scraper.internal.getSignature, Json.obj("url" -> url, "proxy" -> Json.toJson(proxy), "extractorProviderType" -> extractorProviderType.map(_.name))).map { r =>
      r.json.asOpt[String].map(Signature(_))
    }
  }

  def status(): Seq[Future[(AmazonInstanceInfo, Seq[ScrapeJobStatus])]] = {
    broadcastWithUrls(Scraper.internal.status()).map { f =>
      f map { serviceResponse =>
        (serviceResponse.uri.serviceInstance.instanceInfo, Json.fromJson[Seq[ScrapeJobStatus]](serviceResponse.response.json).get)
      }
    }
  }

  def getThreadDetails(filterState: Option[String]): Seq[Future[ScraperThreadInstanceInfo]] = {
    broadcastWithUrls(Scraper.internal.status()).map { f =>
      f map { serviceResponse =>
        val jobStatus = Json.fromJson[Seq[ScrapeJobStatus]](serviceResponse.response.json).get
        val taskDetails = jobStatus.map { status =>
          ScraperTaskDetails(name = status.worker, url = status.uri.url, submitDateTime = status.submit, callDateTime = None, taskType = ScraperTaskType.SCRAPE, uriId = status.uri.id, scrapeId = status.info.id, None, None)
        }
        val threadDetails = taskDetails.map(task => ScraperThreadDetails(None, None, Left(task)))
        ScraperThreadInstanceInfo(serviceResponse.uri.serviceInstance.instanceInfo, Left(threadDetails))
      }
    }
  }

  def getPornDetectorModel(): Future[Map[String, Float]] = {
    call(Scraper.internal.getPornDetectorModel()).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def detectPorn(query: String): Future[Map[String, Float]] = {
    val payload = Json.obj("query" -> query)
    call(Scraper.internal.detectPorn(), payload).map { r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def whitelist(words: String): Future[String] = {
    val payload = Json.obj("whitelist" -> words)
    call(Scraper.internal.whitelist(), payload).map { r =>
      Json.fromJson[String](r.json).get
    }
  }

  def getEmbedlyImageInfos(uriId: Id[NormalizedURI], url: String): Future[Seq[ImageInfo]] = {
    val payload = Json.obj("uriId" -> uriId.id, "url" -> url)
    call(Scraper.internal.getEmbedlyImageInfos, payload).map { r =>
      Json.fromJson[Seq[ImageInfo]](r.json).get
    }
  }

  def getURISummaryFromEmbedly(uri: NormalizedURIRef, descriptionOnly: Boolean): Future[Option[URISummary]] = {
    val payload = Json.obj("uri" -> uri, "descriptionOnly" -> descriptionOnly)
    call(Scraper.internal.getURISummaryFromEmbedly, payload, callTimeouts = superExtraLongTimeoutJustForEmbedly).map { r =>
      r.json.as[Option[URISummary]]
    }
  }

  def getURIWordCount(uriId: Id[NormalizedURI], url: Option[String]): Future[Int] = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    cacheProvider.wordCountCache.get(NormalizedURIWordCountKey(uriId)) match {
      case Some(cnt) => Future.successful(cnt)
      case None => {
        val payload = Json.obj("uriId" -> uriId, "url" -> url)
        call(Scraper.internal.getURIWordCount, payload) map { r => r.json.as[Int] }
      }
    }
  }

  def getURIWordCountOpt(uriId: Id[NormalizedURI], url: Option[String]): Option[Int] = {
    import com.keepit.common.cache.TransactionalCaching.Implicits.directCacheAccess

    log.info(s"Requesting word count from cache for uri $uriId with url $url")
    cacheProvider.wordCountCache.get(NormalizedURIWordCountKey(uriId)) match {
      case Some(cnt) => {
        log.info(s"Found word count $cnt for uri $uriId with url $url")
        Some(cnt)
      }
      case None => {
        log.info(s"Requesting word count from scraper for uri $uriId with url $url")
        val payload = Json.obj("uriId" -> uriId, "url" -> url)
        call(Scraper.internal.getURIWordCount, payload) // Word count will be added to cache
        None
      }
    }
  }
}


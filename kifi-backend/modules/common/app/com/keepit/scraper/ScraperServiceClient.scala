package com.keepit.scraper


//import play.api.libs.concurrent.Execution.Implicits.defaultContext

import java.io.IOException
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.{CallTimeouts, HttpClient}
import com.keepit.common.zookeeper.ServiceCluster
import scala.concurrent.Future
import com.google.inject.Inject
import com.google.inject.util.Providers
import com.keepit.common.routes.{Common, Scraper}
import com.keepit.search.Article
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.common.amazon.AmazonInstanceInfo
import org.joda.time.DateTime
import akka.actor.Scheduler


case class ScrapeTuple(uri:NormalizedURI, articleOpt:Option[Article])
object ScrapeTuple {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'article).formatNullable[Article]
  )(ScrapeTuple.apply _, unlift(ScrapeTuple.unapply))
}

case class ScrapeRequest(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy]) {
  override def toString = s"(${uri.toShortString},${info.toShortString},$proxyOpt)"
  def toShortString = s"(${uri.id},${info.id},${uri.url.take(50)}"
}

object ScrapeRequest {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'scrapeInfo).format[ScrapeInfo] and
    (__ \ 'proxy).formatNullable[HttpProxy]
  )(ScrapeRequest.apply _, unlift(ScrapeRequest.unapply))
}

case class ScraperTaskType(value: String)
object ScraperTaskType {
  val SCRAPE = ScraperTaskType("scrape")
  val FETCH_BASIC = ScraperTaskType("fetch_basic")
  val UNKNOWN = ScraperTaskType("unknown")
  val ALL:Seq[ScraperTaskType] = Seq(SCRAPE, FETCH_BASIC)
  implicit val format = new Format[ScraperTaskType] {
    def reads(json: JsValue) = JsSuccess(ALL.find(_.value == json.asOpt[String].getOrElse("")) getOrElse UNKNOWN)
    def writes(taskType: ScraperTaskType) = JsString(taskType.value)
  }
}

case class ScraperTaskDetails(
  name:String,
  url:String,
  submitDateTime:DateTime,
  callDateTime:DateTime,
  taskType:ScraperTaskType,
  uriId:Option[Id[NormalizedURI]],
  scrapeId:Option[Id[ScrapeInfo]],
  killCount:Option[Int],
  extractor:Option[String])
object ScraperTaskDetails {
  implicit val format = (
    (__ \ 'name).format[String] and
    (__ \ 'url).format[String] and
    (__ \ 'submitDateTime).format[DateTime] and
    (__ \ 'callDateTime).format[DateTime] and
    (__ \ 'taskType).format[ScraperTaskType] and
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'scrapeId).formatNullable(Id.format[ScrapeInfo]) and
    (__ \ 'killCount).formatNullable[Int] and
    (__ \ 'extractor).formatNullable[String]
    )(ScraperTaskDetails.apply _, unlift(ScraperTaskDetails.unapply))
}

case class ScraperThreadDetails(state:Option[String], share:Option[String], description:Either[ScraperTaskDetails,String])
object ScraperThreadDetails {
  def buildFromString(details:String): Option[ScraperThreadDetails] = {
    val re = """^(.*?)\s+(\w+)\s+([0-9]*\.?[0-9]*%)\s+share""".r
    re findFirstIn details match {
      case Some(re(task,state,share)) => {
        try Some(ScraperThreadDetails(Some(state), Some(share), Left(Json.parse(task).as[ScraperTaskDetails])))
        catch { case (_:IOException | _:JsResultException) => Some(ScraperThreadDetails(Some(state), Some(share), Right(task))) }
      }
      case None => None
    }
  }
}

case class ScraperThreadInstanceInfo(info:AmazonInstanceInfo, details:String) {
  lazy val forkJoinThreadDetails: Seq[ScraperThreadDetails] = {
    (details.lines filter { !_.isEmpty } toList) map { ScraperThreadDetails.buildFromString(_) } collect { case Some(x) => x }
  }
  lazy val scrapeThreadDetails: Seq[ScraperThreadDetails] = threadDetailsForTaskType(ScraperTaskType.SCRAPE)
  lazy val fetchBasicThreadDetails: Seq[ScraperThreadDetails] = threadDetailsForTaskType(ScraperTaskType.FETCH_BASIC)
  lazy val otherThreadDetails: Seq[ScraperThreadDetails] = forkJoinThreadDetails.filter(_.description.isRight)

  def threadDetailsForTaskType(taskType:ScraperTaskType): Seq[ScraperThreadDetails] = {
    forkJoinThreadDetails.filter{ threadDetails =>
      threadDetails.description match {
        case Left(details) => details.taskType == taskType
        case Right(_) => false
      }
    }
  }
}

trait ScraperServiceClient extends ServiceClient {
  implicit val fj = com.keepit.common.concurrent.ExecutionContext.fj
  final val serviceType = ServiceType.SCRAPER

  def asyncScrape(uri:NormalizedURI):Future[(NormalizedURI, Option[Article])] // pass in simple url? not sure if Tuple2
  def asyncScrapeWithInfo(uri:NormalizedURI, info:ScrapeInfo):Future[(NormalizedURI, Option[Article])]
  def asyncScrapeWithRequest(request:ScrapeRequest):Future[(NormalizedURI, Option[Article])]
  def scheduleScrape(uri:NormalizedURI, info:ScrapeInfo):Future[Boolean] // ack
  def scheduleScrapeWithRequest(request:ScrapeRequest):Future[Boolean] // ack
  def getBasicArticle(url:String, proxy:Option[HttpProxy], extractor:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def getSignature(url:String, proxy:Option[HttpProxy], extractor:Option[ExtractorProviderType]):Future[Option[Signature]]
  def getThreadDetails(filterState: Option[String] = None): Seq[Future[ScraperThreadInstanceInfo]]
  def getPornDetectorModel(): Future[Map[String, Float]]
  def detectPorn(query: String): Future[Map[String, Float]]
}

class ScraperServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  defaultHttpClient: HttpClient,
  val serviceCluster: ServiceCluster
) extends ScraperServiceClient with Logging {

  val longTimeout = CallTimeouts(responseTimeout = Some(60000))
  val httpClient = defaultHttpClient.withTimeout(longTimeout)

  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    call(Scraper.internal.asyncScrapeArticle, Json.toJson(uri)).map{ r =>
      val t = r.json.as[ScrapeTuple]
      (t.uri, t.articleOpt)
    }
  }

  def asyncScrapeWithInfo(uri: NormalizedURI, info:ScrapeInfo): Future[(NormalizedURI, Option[Article])] = {
    call(Scraper.internal.asyncScrapeArticleWithInfo, JsArray(Seq(Json.toJson(uri), Json.toJson(info)))).map{ r =>
      val t = r.json.as[ScrapeTuple]
      (t.uri, t.articleOpt)
    }
  }

  def asyncScrapeWithRequest(request: ScrapeRequest): Future[(NormalizedURI, Option[Article])] = {
    call(Scraper.internal.asyncScrapeArticleWithRequest, Json.toJson(request)).map { r =>
      val t = r.json.as[ScrapeTuple]
      (t.uri, t.articleOpt)
    }
  }

  def scheduleScrape(uri: NormalizedURI, info: ScrapeInfo): Future[Boolean] = {
    call(Scraper.internal.scheduleScrape, JsArray(Seq(Json.toJson(uri), Json.toJson(info)))).map { r =>
      r.json.as[JsBoolean].value
    }
  }

  def scheduleScrapeWithRequest(request: ScrapeRequest): Future[Boolean] = {
    call(Scraper.internal.scheduleScrapeWithRequest, Json.toJson(request)).map { r =>
      r.json.as[JsBoolean].value
    }
  }

  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    call(Scraper.internal.getBasicArticle, Json.obj("url" -> url, "proxy" -> Json.toJson(proxy), "extractorProviderType" -> extractorProviderType.map(_.name))).map{ r =>
      r.json.validate[BasicArticle].asOpt
    }
  }

  def getSignature(url: String, proxy: Option[HttpProxy], extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = {
    call(Scraper.internal.getSignature, Json.obj("url" -> url, "proxy" -> Json.toJson(proxy), "extractorProviderType" -> extractorProviderType.map(_.name))).map{ r =>
      r.json.asOpt[String].map(Signature(_))
    }
  }

  def getThreadDetails(filterState: Option[String]): Seq[Future[ScraperThreadInstanceInfo]] = {
    broadcastWithUrls(Common.internal.threadDetails(Some("ForkJoinPool"), filterState)) map { _ map {response => ScraperThreadInstanceInfo(response.uri.serviceInstance.instanceInfo, response.response.body) } }
  }

  def getPornDetectorModel(): Future[Map[String, Float]] = {
    call(Scraper.internal.getPornDetectorModel()).map{ r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }

  def detectPorn(query: String): Future[Map[String, Float]] = {
    val payload = Json.obj("query" -> query)
    call(Scraper.internal.detectPorn(), payload).map{ r =>
      Json.fromJson[Map[String, Float]](r.json).get
    }
  }
}

class FakeScraperServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ScraperServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, ()=>{})

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = ???

  def asyncScrapeWithInfo(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = ???

  def asyncScrapeWithRequest(request: ScrapeRequest): Future[(NormalizedURI, Option[Article])] = ???

  def scheduleScrape(uri: NormalizedURI, info: ScrapeInfo): Future[Boolean] = ???

  def scheduleScrapeWithRequest(request: ScrapeRequest): Future[Boolean] = ???

  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = ???

  def getSignature(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[Signature]] = ???

  def getThreadDetails(filterState: Option[String]): Seq[Future[ScraperThreadInstanceInfo]] = ???

  def getPornDetectorModel(): Future[Map[String, Float]] = ???

  def detectPorn(query: String): Future[Map[String, Float]] = ???
}

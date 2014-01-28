package com.keepit.scraper


import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.model._
import com.keepit.common.db.Id
import com.keepit.common.service.{ServiceClient, ServiceType}
import com.keepit.common.logging.Logging
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.net.HttpClient
import com.keepit.common.zookeeper.ServiceCluster
import scala.concurrent.{Future, Promise}
import play.api.libs.json._
import com.google.inject.{ImplementedBy, Inject}
import com.google.inject.util.Providers
import com.keepit.common.routes.{Common, Scraper}
import com.keepit.search.Article
import play.api.libs.json.JsArray
import play.api.libs.functional.syntax._
import play.api.libs.json._
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.common.amazon.AmazonInstanceInfo
import scala.util.matching.Regex
import org.joda.time.DateTime
import org.codehaus.jackson.JsonProcessingException

case class ScrapeTuple(uri:NormalizedURI, articleOpt:Option[Article])
object ScrapeTuple {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'article).formatNullable[Article]
  )(ScrapeTuple.apply _, unlift(ScrapeTuple.unapply))
}


case class ScrapeRequest(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])
object ScrapeRequest {
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'scrapeInfo).format[ScrapeInfo] and
    (__ \ 'proxy).formatNullable[HttpProxy]
  )(ScrapeRequest.apply _, unlift(ScrapeRequest.unapply))
}

case class ScraperTaskDetails(
  uriId:Option[Id[NormalizedURI]],
  scrapeId:Option[Id[ScrapeInfo]],
  url:String,
  submitDateTime:DateTime,
  callDateTime:DateTime,
  killCount:Int)
object ScraperTaskDetails {
  implicit val format = (
    (__ \ 'uriId).formatNullable(Id.format[NormalizedURI]) and
    (__ \ 'scrapeId).formatNullable(Id.format[ScrapeInfo]) and
    (__ \ 'url).format[String] and
    (__ \ 'submitDateTime).format[DateTime] and
    (__ \ 'callDateTime).format[DateTime] and
    (__ \ 'killCount).format[Int]
  )(ScraperTaskDetails.apply _, unlift(ScraperTaskDetails.unapply))
}

case class ScraperThreadDetails(name:String, state:Option[String], share:Option[String], task:Option[ScraperTaskDetails])
object ScraperThreadDetails {
  def buildFromString(details:String): ScraperThreadDetails = {
    val reWithTask = """^(ForkJoinPool\S*)##\[Task:(.*)\]\s+(\w+)\s+(.*)\s+share""".r
    val reNoTask = """^(ForkJoinPool\S*)\s+(\w+)\s+(.*)\s+share""".r
    reWithTask findFirstIn details match {
      case Some(reWithTask(name,task,state,share)) => {
        try ScraperThreadDetails(name, Some(state), Some(share), Json.parse(task).asOpt[ScraperTaskDetails])
        catch { case _:JsonProcessingException => ScraperThreadDetails(name, Some(state), Some(share), None) }
      }
      case None => reNoTask findFirstIn details match {
        case Some(reNoTask(name,state,share)) => ScraperThreadDetails(name, Some(state), Some(share), None)
        case None => ScraperThreadDetails(details, None, None, None)
      }
    }
  }
}

case class ScraperThreadInstanceInfo(info:AmazonInstanceInfo, details:String) {
  lazy val forkJoinThreadDetails: Seq[ScraperThreadDetails] = {
    (details.lines filter { !_.isEmpty } toList) map { ScraperThreadDetails.buildFromString(_) }
  }
}

trait ScraperServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SCRAPER

  def asyncScrape(uri:NormalizedURI):Future[(NormalizedURI, Option[Article])] // pass in simple url? not sure if Tuple2
  def asyncScrapeWithInfo(uri:NormalizedURI, info:ScrapeInfo):Future[(NormalizedURI, Option[Article])]
  def asyncScrapeWithRequest(request:ScrapeRequest):Future[(NormalizedURI, Option[Article])]
  def scheduleScrape(uri:NormalizedURI, info:ScrapeInfo):Future[Boolean] // ack
  def scheduleScrapeWithRequest(request:ScrapeRequest):Future[Boolean] // ack
  def getBasicArticle(url:String, proxy:Option[HttpProxy], extractor:Option[ExtractorProviderType]):Future[Option[BasicArticle]]
  def getSignature(url:String, proxy:Option[HttpProxy], extractor:Option[ExtractorProviderType]):Future[Option[Signature]]
  def getThreadDetails(filterState: Option[String] = None): Seq[Future[ScraperThreadInstanceInfo]]
}

class ScraperServiceClientImpl @Inject() (
  val airbrakeNotifier: AirbrakeNotifier,
  val httpClient: HttpClient,
  val serviceCluster: ServiceCluster
) extends ScraperServiceClient with Logging {

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
}

class FakeScraperServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ScraperServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier))

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = ???

  def asyncScrapeWithInfo(uri: NormalizedURI, info: ScrapeInfo): Future[(NormalizedURI, Option[Article])] = ???

  def asyncScrapeWithRequest(request: ScrapeRequest): Future[(NormalizedURI, Option[Article])] = ???

  def scheduleScrape(uri: NormalizedURI, info: ScrapeInfo): Future[Boolean] = ???

  def scheduleScrapeWithRequest(request: ScrapeRequest): Future[Boolean] = ???

  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = ???

  def getSignature(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[Signature]] = ???

  def getThreadDetails(filterState: Option[String]): Seq[Future[ScraperThreadInstanceInfo]] = ???
}

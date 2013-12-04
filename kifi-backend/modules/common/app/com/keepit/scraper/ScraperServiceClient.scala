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
import com.keepit.common.routes.Scraper
import com.keepit.search.Article
import play.api.libs.json.JsArray
import play.api.libs.functional.syntax._
import play.api.libs.json._

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

trait ScraperServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SCRAPER

  def asyncScrape(uri:NormalizedURI):Future[(NormalizedURI, Option[Article])] // pass in simple url? not sure if Tuple2
  def asyncScrapeWithInfo(uri:NormalizedURI, info:ScrapeInfo):Future[(NormalizedURI, Option[Article])]
  def asyncScrapeWithRequest(request:ScrapeRequest):Future[(NormalizedURI, Option[Article])]
  def scheduleScrape(uri:NormalizedURI, info:ScrapeInfo):Future[Boolean] // ack
  def scheduleScrapeWithRequest(request:ScrapeRequest):Future[Boolean] // ack
  def getBasicArticle(url:String):Future[Option[BasicArticle]]
  def getBasicArticleP(url:String, proxy:Option[HttpProxy]):Future[Option[BasicArticle]]
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

  def getBasicArticle(url: String): Future[Option[BasicArticle]] = {
    call(Scraper.internal.getBasicArticle(url)).map{ r =>
      r.json.validate[BasicArticle].asOpt
    }
  }

  def getBasicArticleP(url: String, proxy: Option[HttpProxy]): Future[Option[BasicArticle]] = {
    call(Scraper.internal.getBasicArticleP, Json.obj("url" -> url, "proxy" -> Json.toJson(proxy))).map{ r =>
      r.json.validate[BasicArticle].asOpt
    }
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

  def getBasicArticle(url: String): Future[Option[BasicArticle]] = ???

  def getBasicArticleP(url: String, proxy: Option[HttpProxy]): Future[Option[BasicArticle]] = ???
}

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
import play.api.libs.json.{JsValue, JsArray, Json, JsObject}
import com.google.inject.{ImplementedBy, Inject}
import com.keepit.common.routes.Scraper
import com.keepit.search.Article

case class ScrapeTuple(uri:NormalizedURI, articleOpt:Option[Article])
object ScrapeTuple {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  implicit val format = (
    (__ \ 'normalizedUri).format[NormalizedURI] and
    (__ \ 'article).formatNullable[Article]
  )(ScrapeTuple.apply _, unlift(ScrapeTuple.unapply))
}

trait ScraperServiceClient extends ServiceClient {
  final val serviceType = ServiceType.SCRAPER

  def asyncScrape(uri:NormalizedURI):Future[(NormalizedURI, Option[Article])] // pass in simple url? not sure if Tuple2
  def getBasicArticle(url:String):Future[Option[BasicArticle]]
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

  def getBasicArticle(url: String): Future[Option[BasicArticle]] = {
    call(Scraper.internal.getBasicArticle(url)).map{ r =>
      r.json.validate[BasicArticle].asOpt
    }
  }

}

class FakeScraperServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier) extends ScraperServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE)

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = ???

  def getBasicArticle(url: String): Future[Option[BasicArticle]] = ???

}

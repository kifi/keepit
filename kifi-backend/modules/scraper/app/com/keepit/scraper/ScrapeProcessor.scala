package com.keepit.scraper

import com.keepit.model.{ PageInfo, ScrapeInfo, NormalizedURI, HttpProxy }
import com.keepit.scraper.extractor.ExtractorProviderType
import org.joda.time.DateTime

import scala.concurrent.Future

trait ScrapeProcessor {
  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]]
  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit
  def status(): Future[Seq[ScrapeJobStatus]] = Future.successful(Seq.empty)
  def pull(): Unit = {}
}

case class ScrapeJobStatus(worker: String, submit: DateTime, uri: NormalizedURI, info: ScrapeInfo)

object ScrapeJobStatus {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val format = (
    (__ \ 'worker).format[String] and
    (__ \ 'submitTS).format[DateTime] and
    (__ \ 'uri).format[NormalizedURI] and
    (__ \ 'scrape).format[ScrapeInfo]
  )(ScrapeJobStatus.apply _, unlift(ScrapeJobStatus.unapply))
}

package com.keepit.scraper

import akka.actor.Scheduler
import com.google.inject.util.Providers
import com.keepit.common.amazon.AmazonInstanceInfo
import com.keepit.common.db.Id
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.service.ServiceType
import com.keepit.common.store.ImageSize
import com.keepit.common.zookeeper.ServiceCluster
import com.keepit.model._
import com.keepit.scraper.embedly.EmbedlyInfo
import com.keepit.scraper.extractor.ExtractorProviderType

import scala.concurrent.Future

class FakeScraperServiceClientImpl(val airbrakeNotifier: AirbrakeNotifier, scheduler: Scheduler) extends ScraperServiceClient {

  val serviceCluster: ServiceCluster = new ServiceCluster(ServiceType.TEST_MODE, Providers.of(airbrakeNotifier), scheduler, () => {})

  protected def httpClient: com.keepit.common.net.HttpClient = ???

  override def status(): Seq[Future[(AmazonInstanceInfo, Seq[ScrapeJobStatus])]] = Seq.empty

  def getBasicArticle(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = ???

  def getSignature(url: String, proxy: Option[HttpProxy], extractor: Option[ExtractorProviderType]): Future[Option[Signature]] = ???

  def getThreadDetails(filterState: Option[String]): Seq[Future[ScraperThreadInstanceInfo]] = ???

  def getPornDetectorModel(): Future[Map[String, Float]] = ???

  def detectPorn(query: String): Future[Map[String, Float]] = ???

  def whitelist(words: String): Future[String] = ???

  def getEmbedlyImageInfos(uriId: Id[NormalizedURI], url: String): Future[Seq[ImageInfo]] = ???

  def getURISummaryFromEmbedly(uri: NormalizedURI, minSize: ImageSize, descriptionOnly: Boolean): Future[Option[URISummary]] = Future.successful(None)

  def getURIWordCount(uriId: Id[NormalizedURI], url: Option[String]): Future[Int] = Future.successful(0)

  def getURIWordCountOpt(uriId: Id[NormalizedURI], url: Option[String]): Option[Int] = ???
}

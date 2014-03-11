package com.keepit.scraper

import com.google.inject.{Provider, Inject}
import akka.actor.{Props, ActorSystem}
import akka.routing.SmallestMailboxRouter
import akka.util.Timeout
import akka.pattern.ask
import com.keepit.model.{PageInfo, ScrapeInfo, NormalizedURI, HttpProxy}
import com.keepit.scraper.extractor.{LinkedInIdExtractor, ExtractorProviderTypes, ExtractorFactory, ExtractorProviderType}
import scala.concurrent.Future
import com.keepit.search.{ArticleStore, Article}
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.logging.Logging
import org.apache.http.HttpStatus
import com.keepit.common.performance._

class SyncScrapeProcessor @Inject() (config:ScraperConfig, sysProvider:Provider[ActorSystem], procProvider:Provider[SyncScraperActor], nrOfInstances:Int) extends ScrapeProcessor {

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(procProvider.get).withRouter(SmallestMailboxRouter(nrOfInstances)))

  implicit val timeout = Timeout(config.actorTimeout)

  def fetchBasicArticle(url: String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    (actor ? FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)).mapTo[Option[BasicArticle]]
  }

  def scrapeArticle(uri: NormalizedURI, info: ScrapeInfo, proxyOpt:Option[HttpProxy]): Future[(NormalizedURI, Option[Article])] = {
    (actor ? ScrapeArticle(uri, info, proxyOpt)).mapTo[(NormalizedURI, Option[Article])]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfoOpt:Option[PageInfo], proxyOpt:Option[HttpProxy]): Unit = {
    actor ! AsyncScrape(uri, info, pageInfoOpt, proxyOpt)
  }
}

case class AsyncScrape(uri:NormalizedURI, info:ScrapeInfo, pageInfo:Option[PageInfo], proxyOpt:Option[HttpProxy])
case class FetchBasicArticle(url:String, proxyOpt:Option[HttpProxy], extractorProviderTypeOpt:Option[ExtractorProviderType])
case class ScrapeArticle(uri:NormalizedURI, info:ScrapeInfo, proxyOpt:Option[HttpProxy])

class SyncScraperActor @Inject() (
  airbrake:AirbrakeNotifier,
  config: ScraperConfig,
  syncScraper: SyncScraper,
  httpFetcher: HttpFetcher,
  extractorFactory: ExtractorFactory,
  articleStore: ArticleStore,
  s3ScreenshotStore: S3ScreenshotStore,
  helper: SyncShoeboxDbCallbacks) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScraperProcessor-actor] created $this")

  implicit val myConfig = config

  def receive = {

    case FetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt) => {
      log.info(s"[FetchArticle] message received; url=$url")
      val ts = System.currentTimeMillis
      val extractor = extractorProviderTypeOpt match {
        case Some(t) if (t == ExtractorProviderTypes.LINKEDIN_ID) => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
        case _ => extractorFactory(url)
      }
      val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
      val res = fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !(helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl)) => Some(syncScraper.basicArticle(url, extractor))
        case _ => None
      }
      log.info(s"[FetchArticle] time-lapsed:${System.currentTimeMillis - ts} url=$url result=$res")
      sender ! res
    }

    case ScrapeArticle(uri, info, proxyOpt) => {
      log.info(s"[ScrapeArticle] message received; url=${uri.url}")
      val ts = System.currentTimeMillis
      val res = syncScraper.safeProcessURI(uri, info, None, proxyOpt)
      log.info(s"[ScrapeArticle] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=${res._1.state}")
      sender ! res
    }
    case AsyncScrape(nuri, info, pageInfoOpt, proxyOpt) => timing(s"AsyncScrape: uri=(${nuri.id}, ${nuri.url}) info=(${info.id},${info.destinationUrl}) proxy=$proxyOpt") {
      log.info(s"[AsyncScrape] message received; url=${nuri.url}")
      val ts = System.currentTimeMillis
      val (uri, a) = syncScraper.safeProcessURI(nuri, info, pageInfoOpt, proxyOpt)
      log.info(s"[AsyncScrape] time-lapsed:${System.currentTimeMillis - ts} url=${uri.url} result=(${uri.id}, ${uri.state})")
    }
  }

}

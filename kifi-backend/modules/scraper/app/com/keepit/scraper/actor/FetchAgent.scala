package com.keepit.scraper.actor

import akka.actor._
import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.scraper.extractor.{ ExtractorFactory, ExtractorProviderType, ExtractorProviderTypes, LinkedInIdExtractor }
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.scraper.{ ScrapeWorker, ScraperConfig, SyncShoeboxDbCallbacks }
import org.apache.http.HttpStatus

class FetchAgent @Inject() (
    airbrake: AirbrakeNotifier,
    helper: SyncShoeboxDbCallbacks,
    extractorFactory: ExtractorFactory,
    httpFetcher: HttpFetcher,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  import akka.pattern.pipe
  import ScraperMessages.Fetch
  import InternalMessages.FetchJob
  implicit val fj = ExecutionContext.fj

  val name = self.path.name
  log.info(s"[FetchAgent($name)] created! parent=${context.parent} props=${context.props} context=${context}")

  def receive: Receive = {
    case FetchJob(submitTS, Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])) =>
      fetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt).pipeTo(sender)
    case m => throw new UnsupportedActorMessage(m)
  }
  private def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]) = {
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if t == ExtractorProviderTypes.LINKEDIN_ID => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
      case _ => extractorFactory(url)
    }
    if (URI.parse(url).get.host.isEmpty) throw new IllegalArgumentException(s"url $url has no host!")
    httpFetcher.get(url, proxy = proxyOpt)(input => extractor.process(input)) map { fetchStatus =>
      fetchStatus.statusCode match {
        case HttpStatus.SC_OK if !helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl) =>
          Some(extractor.basicArticle(fetchStatus.destinationUrl getOrElse url))
        case _ => None
      }
    }
  }
}

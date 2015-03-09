package com.keepit.scraper.actor

import com.google.inject.Inject
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.model.HttpProxy
import com.keepit.scraper.extractor.{ ExtractorFactory, ExtractorProviderType, ExtractorProviderTypes, LinkedInIdExtractor }
import com.keepit.scraper.fetcher.DeprecatedHttpFetcher
import com.keepit.scraper._
import play.api.http.Status

import scala.concurrent.Future

class FetchAgent @Inject() (
    airbrake: AirbrakeNotifier,
    shoeboxCommander: ShoeboxCommander,
    uriCommander: URICommander,
    extractorFactory: ExtractorFactory,
    httpFetcher: DeprecatedHttpFetcher,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging with Status {

  import akka.pattern.pipe
  import ScraperMessages.Fetch
  import InternalMessages.FetchJob
  implicit val fj = ExecutionContext.fj

  val name = self.path.name
  log.info(s"[FetchAgent($name)] created! parent=${context.parent} props=${context.props} context=$context")

  def receive: Receive = {
    case FetchJob(submitTS, Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])) =>
      fetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt).pipeTo(sender)
    case m => throw new UnsupportedActorMessage(m)
  }

  private def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    val uri = URI.parse(url).get
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if t == ExtractorProviderTypes.LINKEDIN_ID => new LinkedInIdExtractor(uri, ScraperConfig.maxContentChars)
      case _ => extractorFactory(uri)
    }
    if (uri.host.isEmpty) throw new IllegalArgumentException(s"url $url has no host!")
    val resF = httpFetcher.get(uri, proxy = proxyOpt)(input => extractor.process(input)) flatMap { fetchStatus =>
      fetchStatus.statusCode match {
        case OK =>
          uriCommander.isUnscrapable(uri, fetchStatus.destinationUrl) map { isUnscrapable =>
            if (isUnscrapable) None
            else Some(extractor.basicArticle(fetchStatus.destinationUrl getOrElse url))
          }
        case _ => Future.successful(None)
      }
    }
    resF
  }
}

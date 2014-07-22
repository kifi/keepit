package com.keepit.scraper.actor

import akka.actor.{ Props, ActorSystem }
import akka.routing.SmallestMailboxRouter
import akka.util.Timeout
import com.google.inject.{ Inject, Provider, Singleton }
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.{ HttpProxy, NormalizedURI, PageInfo, ScrapeInfo }
import com.keepit.scraper._
import com.keepit.scraper.extractor._
import com.keepit.scraper.fetcher.HttpFetcher
import org.apache.http.HttpStatus

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

@Singleton
class ScrapeProcessorActorImpl @Inject() (
    sysProvider: Provider[ActorSystem],
    scrapeSupervisorProvider: Provider[ScrapeAgentSupervisor],
    scrapeProcActorProvider: Provider[ScrapeAgent],
    serviceDiscovery: ServiceDiscovery,
    asyncHelper: ShoeboxDbCallbacks) extends ScrapeProcessor with Logging {

  import akka.pattern.ask
  import ScraperMessages._

  implicit val timeout = Timeout(5 minutes)

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(scrapeSupervisorProvider.get), "scraper_supervisor")
  log.info(s"[ScrapeProcessorActorImpl] created! sys=$system actor=$actor")

  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    actor.ask(Fetch(url, proxyOpt, extractorProviderTypeOpt)).mapTo[Option[BasicArticle]]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit = {
    actor ! Scrape(uri, info, pageInfo, proxyOpt)
  }

  override def pull(): Unit = {
    val numEmptyMailboxes = MonitoredMailbox.numEmptyMailboxes // approx.
    if (numEmptyMailboxes != 0) {
      log.info(s"[ScrapeProcessorActorImpl.pull] numEmptyMailboxes=$numEmptyMailboxes. Let's get some work.")
      serviceDiscovery.thisInstance.map { inst =>
        if (inst.isHealthy) {
          asyncHelper.assignTasks(inst.id.id, numEmptyMailboxes * 2).onComplete {
            case Failure(t) =>
              log.error(s"[ScrapeProcessorActorImpl.pull(${inst.id.id})] Caught exception $t while pulling for tasks", t) // move along
            case Success(requests) =>
              log.info(s"[ScrapeProcessorActorImpl.pull(${inst.id.id})] assigned (${requests.length}) scraping tasks: ${requests.map(r => s"[uriId=${r.uri.id},infoId=${r.scrapeInfo.id},url=${r.uri.url}]").mkString(",")} ")
              for (sr <- requests) {
                asyncScrape(sr.uri, sr.scrapeInfo, sr.pageInfoOpt, sr.proxyOpt)
              }
          }(ExecutionContext.fj)
        }
      }
    } else {
      log.info(s"[ScrapeProcessorActorImpl.pull] all agents busy; Skip a round")
    }
  }

}

class ScrapeAgentSupervisor @Inject() (
    airbrake: AirbrakeNotifier,
    sysProvider: Provider[ActorSystem],
    fetcherAgentProvider: Provider[FetchAgent],
    scrapeAgentProvider: Provider[ScrapeAgent]) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"Supervisor.<ctr> created! context=$context")

  import akka.pattern.ask
  import ScraperMessages._

  implicit val fj = ExecutionContext.fj
  implicit val timeout = Timeout(5 minutes)

  lazy val system = sysProvider.get
  log.info(s"[Supervisor.ctr] config=${system.settings.config}")

  val scrapers = (0 until Runtime.getRuntime.availableProcessors() / 8).map { i =>
    context.actorOf(Props(scrapeAgentProvider.get).withDispatcher("monitored-dispatcher"), s"scraper-agent$i")
  }
  val scraperRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = scrapers)), "scraper-router")
  log.info(s"[Supervisor.ctr] scraperRouter=$scraperRouter scrapers=${scrapers.mkString(",")}")

  val fetchers = (0 until Runtime.getRuntime.availableProcessors() / 8).map { i =>
    context.actorOf(Props(fetcherAgentProvider.get))
  }
  val fetcherRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = fetchers)), "scraper-fetcher")
  log.info(s"[Supervisor.ctr] fetcherRouter=$fetcherRouter fetchers=${fetchers.mkString(",")}")

  log.info(s"[Supervisor.ctr] context.children(len=${context.children.size}):${context.children.mkString(",")}")

  def receive = {
    case f: Fetch => {
      fetcherRouter.ask(f).mapTo[Option[BasicArticle]].map { articleOpt =>
        log.info(s"[Supervisor.fetch] article=$articleOpt")
        sender ! articleOpt
      }
    }
    case s: Scrape => {
      scraperRouter ! s
    }
    case m => throw new UnsupportedActorMessage(m)
  }
}

object ScraperMessages {
  case class Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])
  case class Scrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy])
}

import ScraperMessages._

class ScrapeAgent @Inject() (
    airbrake: AirbrakeNotifier,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScrapeAgent] created! parent=${context.parent} props=${context.props} context=${context}")

  def receive = {
    case s: Scrape => {
      val articleOpt = worker.safeProcessURI(s.uri, s.info, s.pageInfo, s.proxyOpt) // this is blocking for sure
      log.info(s"[ScrapeAgent.Scrape] uri=${s.uri.toShortString} article=$articleOpt")
    }
    case m => throw new UnsupportedActorMessage(m)
  }

}

class FetchAgent @Inject() (
    airbrake: AirbrakeNotifier,
    helper: SyncShoeboxDbCallbacks,
    extractorFactory: ExtractorFactory,
    httpFetcher: HttpFetcher,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[FetchAgent] created! parent=${context.parent} props=${context.props} context=${context}")

  def receive: Receive = {
    case Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]) =>
      val articleOpt = fetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)
      log.info(s"[FetchAgent.Fetch] url=$url article=$articleOpt")
      sender ! articleOpt
    case m => throw new UnsupportedActorMessage(m)
  }
  private def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]) = {
    val extractor = extractorProviderTypeOpt match {
      case Some(t) if t == ExtractorProviderTypes.LINKEDIN_ID => new LinkedInIdExtractor(url, ScraperConfig.maxContentChars)
      case _ => extractorFactory(url)
    }
    if (URI.parse(url).get.host.isEmpty) throw new IllegalArgumentException(s"url $url has no host!")
    val fetchStatus = httpFetcher.fetch(url, proxy = proxyOpt)(input => extractor.process(input))
    fetchStatus.statusCode match {
      case HttpStatus.SC_OK if !helper.syncIsUnscrapableP(url, fetchStatus.destinationUrl) =>
        Some(extractor.basicArticle(fetchStatus.destinationUrl getOrElse url))
      case _ => None
    }
  }
}
package com.keepit.scraper.actor

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.{ ActorRef, Props, ActorSystem }
import akka.routing.SmallestMailboxRouter
import akka.util.Timeout
import com.google.inject.{ Inject, Provider, Singleton }
import com.keepit.common.akka.{ SafeFuture, FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model.{ HttpProxy, NormalizedURI, PageInfo, ScrapeInfo }
import com.keepit.scraper._
import com.keepit.scraper.extractor._
import com.keepit.scraper.fetcher.HttpFetcher
import com.keepit.search.Article
import org.apache.http.HttpStatus

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

import ScraperMessages._
import InternalMessages._
import akka.pattern.ask

@Singleton
class ScrapeProcessorActorImpl @Inject() (
    airbrake: AirbrakeNotifier,
    sysProvider: Provider[ActorSystem],
    scrapeSupervisorProvider: Provider[ScrapeAgentSupervisor],
    scrapeProcActorProvider: Provider[ScrapeAgent],
    serviceDiscovery: ServiceDiscovery,
    asyncHelper: ShoeboxDbCallbacks) extends ScrapeProcessor with Logging {

  val PULL_THRESHOLD = Runtime.getRuntime.availableProcessors() / 2
  val WARNING_THRESHOLD = 100

  implicit val fj = ExecutionContext.fj
  implicit val timeout = Timeout(15 seconds)

  lazy val system = sysProvider.get
  lazy val actor = system.actorOf(Props(scrapeSupervisorProvider.get).withDispatcher("monitored-dispatcher"), "scraper_supervisor")
  log.info(s"[ScrapeProcessorActorImpl] created! sys=$system actor=$actor")

  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    actor.ask(Fetch(url, proxyOpt, extractorProviderTypeOpt))(Timeout(15 minutes)).mapTo[Option[BasicArticle]]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit = {
    actor ! Scrape(uri, info, pageInfo, proxyOpt)
  }

  private def getQueueSize(): Future[Int] = actor.ask(QueueSize()).mapTo[Int]

  override def pull(): Unit = {
    getQueueSize() map { qSize =>
      if (qSize < PULL_THRESHOLD) {
        log.info(s"[ScrapeProcessorActorImpl.pull] qSize=$qSize. Let's get some work.")
        serviceDiscovery.thisInstance.map { inst =>
          if (inst.isHealthy) {
            asyncHelper.assignTasks(inst.id.id, PULL_THRESHOLD * 2).onComplete {
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
        if (MonitoredMailbox.aggregateSize > WARNING_THRESHOLD) {
          airbrake.notify(s"qSize=${qSize} has exceeded threshold=$WARNING_THRESHOLD")
        } else {
          log.info(s"[ScrapeProcessorActorImpl.pull] qSize=${qSize}; Skip a round")
        }
      }
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
  implicit val timeout = Timeout(15 minutes)

  lazy val system = sysProvider.get
  log.info(s"[Supervisor.ctr] config=${system.settings.config}")

  val scrapers = (0 until Runtime.getRuntime.availableProcessors()).map { i =>
    context.actorOf(Props(scrapeAgentProvider.get).withDispatcher("monitored-dispatcher"), s"scraper-agent$i")
  }
  val scraperRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = scrapers)), "scraper-router")
  log.info(s"[Supervisor.ctr] scraperRouter=$scraperRouter scrapers=${scrapers.mkString(",")}")

  val fetchers = (0 until Runtime.getRuntime.availableProcessors() / 2).map { i =>
    context.actorOf(Props(fetcherAgentProvider.get))
  }
  val fetcherRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = fetchers)), "scraper-fetcher")
  log.info(s"[Supervisor.ctr] fetcherRouter=$fetcherRouter fetchers=${fetchers.mkString(",")}")

  log.info(s"[Supervisor.ctr] context.children(len=${context.children.size}):${context.children.mkString(",")}")

  val scrapeJobs = Map[ActorRef, Scrape]()
  val scrapeQ = new ConcurrentLinkedQueue[Scrape]()

  // todo(ray): +watch

  def receive = {
    case qs: QueueSize =>
      sender ! scrapeQ.size()
    case wa: WorkerAvail =>
      val worker = sender
      log.info(s"[Supervisor] <WorkerAvail> $worker")
      if (!scrapeQ.isEmpty) {
        val s = scrapeQ.poll
        log.info(s"[Supervisor] assign job $s to worker $worker")
        worker ! s
      }
    case WorkerBusy(s) =>
      log.info(s"[Supervisor] <WorkerBusy> worker=$sender is busy; job($s) rejected")
      self ! s
    case f: Fetch =>
      val capturedSender = sender
      fetcherRouter.ask(f).mapTo[Option[BasicArticle]].map { articleOpt =>
        log.info(s"[Supervisor.fetch] article=$articleOpt")
        capturedSender ! articleOpt
      }
    case s: Scrape =>
      scrapeQ.offer(s)
      scraperRouter ! JobAvail()
    case m => throw new UnsupportedActorMessage(m)
  }
}

object InternalMessages {
  // master => worker
  case class JobAvail()
  case class Assign()

  // worker => master
  case class WorkerAvail()
  case class WorkerBusy(s: Scrape)
  case class JobDone(s: Scrape, res: Option[Article]) {
    override def toString = s"JobDone(job=$s,result=${res.map(_.title)})"
  }

  // processor => master (informational; pulling)
  case class QueueSize()
}

object ScraperMessages {
  case class Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])
  case class Scrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]) {
    override def toString = s"Scrape(uri=${uri.toShortString},info=${info.toShortString})"
  }
}

class ScrapeAgent @Inject() (
    airbrake: AirbrakeNotifier,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"[ScrapeAgent] created! parent=${context.parent} props=${context.props} context=${context}")

  implicit val timeout = Timeout(10 seconds)
  import context._

  val parent = context.parent
  parent ! WorkerAvail()

  val name = context.system.name

  def idle: Receive = {
    case p: JobAvail =>
      sender ! WorkerAvail()
    case s: Scrape =>
      log.info(s"[ScrapeAgent($name).idle] got work to do: $s")
      context.become(busy(s))
      SafeFuture {
        val res = worker.safeProcessURI(s.uri, s.info, s.pageInfo, s.proxyOpt)
        self ! JobDone(s, res)
      }
    case m => log.info(s"[ScrapeAgent($name).idle] ignore event $m")
  }

  def busy(s: Scrape): Receive = {
    case p: JobAvail => log.info(s"[ScrapeAgent.busy] ignore <JobAvail> event")
    case d: JobDone =>
      log.info(s"[ScrapeAgent($name).busy] <JobDone> $d")
      context.become(idle) // check whether unbecome is needed
      parent ! WorkerAvail()
    case s: Scrape =>
      log.warn(s"[ScrapeAgent($name).busy] ignore <Scrape> job: $s")
      parent ! WorkerBusy(s)
    case m => log.info(s"[ScrapeAgent($name).busy] ignore event $m")
  }

  def receive = idle

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
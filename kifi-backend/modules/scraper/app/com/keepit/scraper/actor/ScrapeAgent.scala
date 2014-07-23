package com.keepit.scraper.actor

import java.util.concurrent.ConcurrentLinkedQueue

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.routing.{ Broadcast, SmallestMailboxRouter }
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
import com.keepit.common.time._
import org.joda.time.DateTime

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Success, Failure }

import ScraperMessages._
import InternalMessages._
import akka.pattern.ask

// pull-based; see http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2

object InternalMessages {
  // master => worker
  case object JobAvail

  // worker => master
  case class WorkerCreated(worker: ActorRef)
  case class WorkerAvail(worker: ActorRef)
  case class WorkerBusy(worker: ActorRef, s: Scrape)
  case class JobDone(worker: ActorRef, s: Scrape, res: Option[Article]) {
    override def toString = s"JobDone(request=$s,result=${res.map(_.title)})"
  }

  // processor => master (informational; pulling)
  case object QueueSize
}

// Scrape: pure side-effects; Fetch: returns content (see fetchBasicArticle)
object ScraperMessages {
  case class Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])
  case class Scrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]) {
    override def toString = s"Scrape(uri=${uri.toShortString},info=${info.toShortString})"
  }
  case class ScrapeJob(submitTS: DateTime, scrapeRequest: Scrape)
}

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
  lazy val actor = system.actorOf(Props(scrapeSupervisorProvider.get), "scraper_supervisor")

  def fetchBasicArticle(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    actor.ask(Fetch(url, proxyOpt, extractorProviderTypeOpt))(Timeout(15 minutes)).mapTo[Option[BasicArticle]]
  }

  def asyncScrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]): Unit = {
    actor ! Scrape(uri, info, pageInfo, proxyOpt)
  }

  private def getQueueSize(): Future[Int] = actor.ask(QueueSize).mapTo[Int]

  override def pull(): Unit = {
    getQueueSize() onComplete {
      case Success(qSize) =>
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
        } else if (qSize > WARNING_THRESHOLD) {
          airbrake.notify(s"qSize=${qSize} has exceeded threshold=$WARNING_THRESHOLD")
        } else {
          log.info(s"[ScrapeProcessorActorImpl.pull] qSize=${qSize}; Skip a round")
        }
      case Failure(e) =>
        airbrake.notify(s"Failed to obtain qSize from supervisor; exception=$e; cause=${e.getCause}", e)
    }
  }

}

class ScrapeAgentSupervisor @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock,
    sysProvider: Provider[ActorSystem],
    fetcherAgentProvider: Provider[FetchAgent],
    scrapeAgentProvider: Provider[ScrapeAgent]) extends FortyTwoActor(airbrake) with Logging {

  log.info(s"Supervisor.<ctr> created! context=$context")

  implicit val fj = ExecutionContext.fj
  implicit val timeout = Timeout(15 minutes)

  lazy val system = sysProvider.get
  log.info(s"[Supervisor.<ctr>] config=${system.settings.config}")

  val scrapers = (0 until Runtime.getRuntime.availableProcessors()).map { i =>
    context.actorOf(Props(scrapeAgentProvider.get), s"scrape-agent$i")
  }
  val scraperRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = scrapers)), "scraper-router")
  log.info(s"[Supervisor.<ctr>] scraperRouter=$scraperRouter scrapers(sz=${scrapers.size}):${scrapers.mkString(",")}")

  val fetchers = (0 until Runtime.getRuntime.availableProcessors() / 2).map { i =>
    context.actorOf(Props(fetcherAgentProvider.get), s"fetch-agent$i")
  }
  val fetcherRouter = context.actorOf(Props.empty.withRouter(SmallestMailboxRouter(routees = fetchers)), "fetcher-router")
  log.info(s"[Supervisor.<ctr>] fetcherRouter=$fetcherRouter fetchers(sz=${fetchers.size}):${fetchers.mkString(",")}")

  log.info(s"[Supervisor.<ctr>] children(sz=${context.children.size}):${context.children.map(_.path.name).mkString(",")}")

  val scrapeJobs = Map[ActorRef, Scrape]()
  val scrapeQ = new collection.mutable.Queue[ScrapeJob]()

  def receive = {
    case QueueSize =>
      sender ! scrapeQ.size
    case WorkerCreated(worker) =>
      log.info(s"[Supervisor] <WorkerCreated> $worker")
    case WorkerAvail(worker) =>
      log.info(s"[Supervisor] <WorkerAvail> $worker; scrapeQ(sz=${scrapeQ.size}):${scrapeQ.mkString(",")}")
      if (!scrapeQ.isEmpty) {
        val job = scrapeQ.dequeue
        log.info(s"[Supervisor] <WorkerAvail> assign job ${job.scrapeRequest} (submit: ${job.submitTS.toLocalTime}, waited: ${clock.now().getMillis - job.submitTS.getMillis}) to worker $worker")
        worker ! job.scrapeRequest
      }
    case WorkerBusy(worker, s) =>
      log.info(s"[Supervisor] <WorkerBusy> worker=$sender is busy; job($s) rejected")
      self ! s
    case f: Fetch =>
      fetcherRouter.forward(f)
    case s: Scrape =>
      scrapeQ.enqueue(ScrapeJob(clock.now(), s))
      scraperRouter ! Broadcast(JobAvail)
    case job: ScrapeJob =>
      scrapeQ.enqueue(job)
      scraperRouter ! Broadcast(JobAvail)
    case m => throw new UnsupportedActorMessage(m)
  }

  override def supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 15 minutes) {
    case _: IllegalStateException => Restart
    // todo(ray): handle interruption/cancellation
  }

}

class ScrapeAgent @Inject() (
    airbrake: AirbrakeNotifier,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  val name = self.path.name
  log.info(s"[ScrapeAgent($name)] created! parent=${context.parent} props=${context.props} context=${context}")

  implicit val timeout = Timeout(10 seconds)
  import context._

  val parent = context.parent
  parent ! WorkerCreated(self)
  parent ! WorkerAvail(self)

  def idle: Receive = {
    case JobAvail =>
      log.info(s"[ScrapeAgent($name).idle] job is available and I'm idle! Go get some work!")
      parent ! WorkerAvail(self)
    case s: Scrape =>
      log.info(s"[ScrapeAgent($name).idle] got work to do: $s")
      context.become(busy(s))
      SafeFuture {
        worker.safeProcessURI(s.uri, s.info, s.pageInfo, s.proxyOpt) // blocking call (for now)
      } map { res =>
        self ! JobDone(self, s, res)
      }
    case m =>
      log.info(s"[ScrapeAgent($name).idle] ignore event $m")
  }

  def busy(s: Scrape): Receive = {
    case JobAvail =>
      log.info(s"[ScrapeAgent($name).busy] ignore <JobAvail> event")
    case d: JobDone =>
      log.info(s"[ScrapeAgent($name).busy] <JobDone> $d")
      context.become(idle) // check whether unbecome is needed
      parent ! WorkerAvail(self)
    case s: Scrape =>
      log.warn(s"[ScrapeAgent($name).busy] ignore <Scrape> job: $s")
      parent ! WorkerBusy(self, s)
    case m =>
      log.info(s"[ScrapeAgent($name).busy] ignore event $m")
  }

  def receive = idle

}

class FetchAgent @Inject() (
    airbrake: AirbrakeNotifier,
    helper: SyncShoeboxDbCallbacks,
    extractorFactory: ExtractorFactory,
    httpFetcher: HttpFetcher,
    worker: ScrapeWorker) extends FortyTwoActor(airbrake) with Logging {

  val name = self.path.name
  log.info(s"[FetchAgent($name)] created! parent=${context.parent} props=${context.props} context=${context}")

  def receive: Receive = {
    case Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType]) =>
      val articleOpt = fetchBasicArticle(url, proxyOpt, extractorProviderTypeOpt)
      log.info(s"[FetchAgent($name)] <Fetch> url=$url article=$articleOpt")
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
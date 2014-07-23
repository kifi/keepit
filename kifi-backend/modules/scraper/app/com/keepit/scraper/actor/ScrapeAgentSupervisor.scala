package com.keepit.scraper.actor

import akka.actor.SupervisorStrategy.Restart
import akka.actor._
import akka.routing.{ Broadcast, SmallestMailboxRouter }
import akka.util.Timeout
import com.google.inject.{ Inject, Provider }
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.concurrent.ExecutionContext
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.search.Article
import org.joda.time.DateTime

import scala.concurrent.duration._

// pull-based; see http://letitcrash.com/post/29044669086/balancing-workload-across-nodes-with-akka-2
object InternalMessages {

  import ScraperMessages.{ Fetch, Scrape }

  // master => worker
  case object JobAvail
  case class ScrapeJob(submitTS: DateTime, s: Scrape)
  case class FetchJob(submitTS: DateTime, f: Fetch)

  // worker => master
  case class WorkerCreated(worker: ActorRef)
  case class WorkerAvail(worker: ActorRef)
  case class WorkerBusy(worker: ActorRef, job: ScrapeJob)

  // worker => worker (self)
  case class JobDone(job: ScrapeJob, res: Option[Article]) {
    override def toString = s"JobDone(request=$job,result=${res.map(_.title)})"
  }
}

class ScrapeAgentSupervisor @Inject() (
    airbrake: AirbrakeNotifier,
    clock: Clock,
    sysProvider: Provider[ActorSystem],
    fetcherAgentProvider: Provider[FetchAgent],
    scrapeAgentProvider: Provider[ScrapeAgent]) extends FortyTwoActor(airbrake) with Logging {

  import ScraperMessages._
  import InternalMessages._

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
    // internal
    case WorkerCreated(worker) =>
      log.info(s"[Supervisor] <WorkerCreated> $worker")
    case WorkerAvail(worker) =>
      log.info(s"[Supervisor] <WorkerAvail> $worker; scrapeQ(sz=${scrapeQ.size}):${scrapeQ.mkString(",")}")
      if (!scrapeQ.isEmpty) {
        val job = scrapeQ.dequeue
        log.info(s"[Supervisor] <WorkerAvail> assign job ${job.s} (submit: ${job.submitTS.toLocalTime}, waited: ${clock.now().getMillis - job.submitTS.getMillis}) to worker $worker")
        worker ! job
      }
    case WorkerBusy(worker, job) =>
      log.info(s"[Supervisor] <WorkerBusy> worker=$sender is busy; $job rejected")
      self ! job
    case job: ScrapeJob =>
      log.info(s"[Supervisor] <ScrapeJob> enqueue $job")
      scrapeQ.enqueue(job)
      scraperRouter ! Broadcast(JobAvail)

    // external
    case f: Fetch =>
      fetcherRouter.forward(FetchJob(clock.now(), f))
    case s: Scrape =>
      scrapeQ.enqueue(ScrapeJob(clock.now(), s))
      scraperRouter ! Broadcast(JobAvail)
    case QueueSize =>
      sender ! scrapeQ.size
    case m => throw new UnsupportedActorMessage(m)
  }

  override def supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 15 minutes) {
    case _: IllegalStateException => Restart
    // todo(ray): handle interruption/cancellation
  }

}

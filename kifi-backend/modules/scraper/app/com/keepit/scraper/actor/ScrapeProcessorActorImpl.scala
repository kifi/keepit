package com.keepit.scraper.actor

import akka.actor.{ ActorSystem, Props }
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.{ Inject, Provider, Singleton }
import com.keepit.common.concurrent.{ ReactiveLock, ExecutionContext }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model._
import com.keepit.scraper._
import com.keepit.scraper.extractor._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

object ScraperMessages {
  // Scrape: pure side-effects; Fetch: returns content (see fetchBasicArticle)
  case class Fetch(url: String, proxyOpt: Option[HttpProxy], extractorProviderTypeOpt: Option[ExtractorProviderType])
  case class Scrape(uri: NormalizedURI, info: ScrapeInfo, pageInfo: Option[PageInfo], proxyOpt: Option[HttpProxy]) {
    override def toString = s"Scrape(uri=${uri.toShortString},info=${info.toShortString})"
  }
  case object QueueSize // informational; pulling
  case object JobAssignments
}

@Singleton
class ScrapeProcessorActorImpl @Inject() (
    airbrake: AirbrakeNotifier,
    config: ScraperConfig,
    sysProvider: Provider[ActorSystem],
    scrapeSupervisorProvider: Provider[ScrapeAgentSupervisor],
    scrapeProcActorProvider: Provider[ScrapeAgent],
    serviceDiscovery: ServiceDiscovery,
    shoeboxCommander: ShoeboxCommander) extends ScrapeProcessor with Logging {

  import ScraperMessages._

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

  override def status(): Future[Seq[ScrapeJobStatus]] = {
    actor.ask(JobAssignments).mapTo[Seq[ScrapeJobStatus]]
  }

  private[this] def getQueueSize(): Future[Int] = actor.ask(QueueSize).mapTo[Int]

  private[this] val lock = new ReactiveLock(1, None)

  override def pull(): Unit = lock.withLockFuture {
    val futureTask: Future[Unit] = getQueueSize() flatMap { qSize =>
      log.warn(s"[ScrapeProcessorActorImpl.pull] qSize: $qSize")
      if (qSize <= config.pullThreshold) {
        log.info(s"[ScrapeProcessorActorImpl.pull] qSize=$qSize. Let's get some work.")
        val queuedF = serviceDiscovery.thisInstance.map { inst =>
          if (inst.isHealthy) {
            val taskFuture = shoeboxCommander.assignTasks(inst.id.id, 8)
            val queuedFuture = taskFuture map { requests =>
              log.info(s"[ScrapeProcessorActorImpl.pull(${inst.id.id})] assigned (${requests.length}) scraping tasks: ${requests.map(r => s"[uriId=${r.uri.id},infoId=${r.scrapeInfo.id},url=${r.uri.url}]").mkString(",")} ")
              for (sr <- requests) {
                val uri = sr.uri
                URI.parse(uri.url) match {
                  case Success(_) => asyncScrape(uri, sr.scrapeInfo, sr.pageInfoOpt, sr.proxyOpt)
                  case Failure(e) => throw new Exception(s"url can not be parsed for $uri in scrape request $sr", e)
                }
              }
            }
            queuedFuture.onFailure {
              case e =>
                airbrake.notify(s"failed si to parse and queue task", e)
            }
            queuedFuture
          } else {
            log.warn(s"[ScrapeProcessorActorImpl.pull] Instance is not healthy. Returning 0 tasks.")
            Future.successful(())
          }
        }
        queuedF.getOrElse(Future.successful(()))
      } else if (qSize > WARNING_THRESHOLD) {
        airbrake.notify(s"qSize=${qSize} has exceeded threshold=$WARNING_THRESHOLD")
        Future.successful(())
      } else {
        log.info(s"[ScrapeProcessorActorImpl.pull] qSize=${qSize}; Skip a round")
        Future.successful(())
      }
    }
    futureTask.onFailure {
      case e =>
        airbrake.notify(s"Failed to obtain qSize from supervisor", e)
    }
    futureTask
  }
}

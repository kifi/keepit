package com.keepit.scraper

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model._
import scala.concurrent.Future
import akka.util.Timeout
import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.time._
import play.modules.statsd.api.Statsd
import com.keepit.common.plugin.{SchedulerPlugin, SchedulingProperties}
import com.keepit.common.mail.{ElectronicMailCategory, EmailAddresses, ElectronicMail, LocalPostOffice}
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType

case object ScheduleScrape

private[scraper] class ScrapeScheduler @Inject() (
    scraperConfig: ScraperConfig,
    airbrake: AirbrakeNotifier,
    db: Database,
    serviceDiscovery: ServiceDiscovery,
    localPostOffice: LocalPostOffice,
    scrapeInfoRepo: ScrapeInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    scraperServiceClient:ScraperServiceClient
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ScheduleScrape => schedule()
    case m => throw new UnsupportedActorMessage(m)
  }

  implicit val config = scraperConfig

  def schedule(): Unit = {
    val (overdues, pendingCount, pendingOverdues, assignedCount, assignedOverdues) = db.readOnly(attempts = 2) { implicit s =>
      val overdues         = scrapeInfoRepo.getOverdueList()
      val pendingCount     = scrapeInfoRepo.getPendingCount()
      val pendingOverdues  = scrapeInfoRepo.getOverduePendingList(currentDateTime.minusMinutes(config.pendingOverdueThreshold))
      val assignedCount    = scrapeInfoRepo.getAssignedCount()
      val assignedOverdues = scrapeInfoRepo.getOverdueAssignedList(currentDateTime.minusMinutes(config.pendingOverdueThreshold))
      (overdues, pendingCount, pendingOverdues, assignedCount, assignedOverdues)
    }
    log.info(s"[schedule]: active:${overdues.length} pending:${pendingCount} pending-overdues:${pendingOverdues.length} assigned:${assignedCount} assigned-overdues=${assignedOverdues.length}")

    val workers = serviceDiscovery.serviceCluster(ServiceType.SCRAPER).allMembers
    val workerIds = workers.map(_.id.id).toSet
    val (orphaned, assigned) = assignedOverdues partition { info => info.workerId.isDefined } // no workerId (bug)
    if (!orphaned.isEmpty) {
      val msg = s"[schedule] orphaned scraper tasks(${orphaned.length}): ${orphaned.mkString(",")}"
      log.error(msg) // shouldn't happen -- airbrake
      db.readWrite(attempts = 2) { implicit rw =>
        localPostOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY), category = ElectronicMailCategory("scraper"), subject = "scraper-scheduler-orphaned", htmlBody = msg))
        orphaned.foreach { info =>
          scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE))
        }
      }
    }

    val abandoned = assigned filter { info => !workerIds.contains(info.workerId.get.id) } // workers no longer exists
    if (!abandoned.isEmpty) {
      val msg = s"[schedule] abandoned scraper tasks(${abandoned.length}): ${abandoned.mkString(",")}"
      log.warn(msg)
      db.readWrite(attempts = 2) { implicit rw =>
        localPostOffice.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY), category = ElectronicMailCategory("scraper"), subject = "scraper-scheduler-abandoned", htmlBody = msg))
        abandoned.foreach { info =>
          scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE))
        }
      }
    }

    // needs update
    val batchMax = scraperConfig.batchMax
    val pendingSkipThreshold = scraperConfig.pendingSkipThreshold
    val adjPendingCount = (pendingCount - pendingOverdues.length)
    val infos =
      if (adjPendingCount > pendingSkipThreshold) {
        val msg = s"[schedule] # of pending jobs (adj=${adjPendingCount}, pending=${pendingCount}, pendingOverdues=${pendingOverdues.length}) > $pendingSkipThreshold. Skip a round."
        log.warn(msg)
        airbrake.notify(msg)
        Seq.empty[ScrapeInfo]
      } else {
        overdues.take(batchMax) ++ pendingOverdues.take(batchMax)
      }

    val tasks = if (infos.isEmpty) Seq.empty[(NormalizedURI, ScrapeInfo, Option[HttpProxy])]
    else db.readOnly { implicit s =>
      infos.map { info =>
        val uri = normalizedURIRepo.get(info.uriId)
        val proxyOpt = urlPatternRuleRepo.getProxy(uri.url)
        (uri, info, proxyOpt)
      }
    }
    Statsd.gauge("scraper.scheduler.uris.count", tasks.length)
    val ts = System.currentTimeMillis
    tasks foreach { case (uri, info, proxyOpt) =>
      val request = db.readWrite { implicit s =>
        if (uri.state == NormalizedURIStates.ACTIVE
          || uri.state == NormalizedURIStates.INACTIVE
          || uri.state == NormalizedURIStates.UNSCRAPABLE
        ) {
          scrapeInfoRepo.save(info.withState(ScrapeInfoStates.INACTIVE)) // no need to scrape
          None
        } else {
          val savedInfo = scrapeInfoRepo.save(info.withState(ScrapeInfoStates.PENDING)) // todo: assigned
          Some(ScrapeRequest(uri, savedInfo, proxyOpt))
        }
      }
      request foreach { req => scraperServiceClient.scheduleScrapeWithRequest(req) }
    }
    log.info(s"[schedule] submitted ${tasks.length} uris for scraping. time-lapsed:${System.currentTimeMillis - ts}")
  }

}

class ScrapeSchedulerPluginImpl @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    localPostOffice:LocalPostOffice,
    scrapeInfoRepo: ScrapeInfoRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    actor: ActorInstance[ScrapeScheduler],
    scraperConfig: ScraperConfig,
    scraperClient: ScraperServiceClient,
    val scheduling: SchedulingProperties) //only on leader
  extends ScrapeSchedulerPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(scraperConfig.actorTimeout)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info(s"[onStart] starting ScraperPluginImpl with scraperConfig=$scraperConfig}")
    scheduleTaskOnLeader(actor.system, 30 seconds, scraperConfig.scrapePendingFrequency seconds, actor.ref, ScheduleScrape)
  }
  override def onStop() {
    log.info(s"[onStop] ScrapeScheduler stopped")
    super.onStop()
  }

  def scheduleScrape(uri: NormalizedURI)(implicit session: RWSession): Unit = {
    require(uri != null && !uri.id.isEmpty, "[scheduleScrape] <uri> cannot be null and <uri.id> cannot be empty")
    val uriId = uri.id.get
    val info = scrapeInfoRepo.getByUriId(uriId)
    val toSave = info match {
      case Some(s) => s.state match {
        case ScrapeInfoStates.ACTIVE   => s.withNextScrape(currentDateTime)
        case ScrapeInfoStates.PENDING | ScrapeInfoStates.ASSIGNED => s // no change
        case ScrapeInfoStates.INACTIVE => {
          val msg = s"[scheduleScrape($uri.url)] scheduling an INACTIVE ($s) for scraping"
          log.warn(msg)
          localPostOffice.sendMail(ElectronicMail(from = EmailAddresses.RAY, to = List(EmailAddresses.RAY),
            subject = s"ScrapeScheduler.scheduleScrape($uri)",
            htmlBody = s"$msg\n${Thread.currentThread.getStackTrace.mkString("\n")}",
            category = NotificationCategory.System.ADMIN))
          s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime) // dangerous; revisit
        }
      }
      case None => ScrapeInfo(uriId = uriId)
    }
    val saved = scrapeInfoRepo.save(toSave)
    // todo: It may be nice to force trigger a scrape directly
  }

  def scrapeBasicArticle(url: String, extractorProviderType:Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    require(url != null, "[scrapeBasicArticle] <url> cannot be null")
    val proxyOpt = db.readOnly { implicit s =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getBasicArticleWithExtractor(url, proxyOpt, extractorProviderType)
  }
}

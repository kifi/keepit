package com.keepit.scraper

import com.keepit.common.healthcheck.{SystemAdminMailSender, AirbrakeNotifier}
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
import com.keepit.common.db.slick.Database.Slave
import org.joda.time.DateTime

case object ScheduleScrape
case object CheckOverdues

private[scraper] class ScrapeScheduler @Inject() (
    scraperConfig: ScraperConfig,
    airbrake: AirbrakeNotifier,
    db: Database,
    serviceDiscovery: ServiceDiscovery,
    systemAdminMailSender: SystemAdminMailSender,
    scrapeInfoRepo: ScrapeInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    scraperServiceClient:ScraperServiceClient
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case ScheduleScrape => schedule()
    case CheckOverdues => checkOverdues()
    case m => throw new UnsupportedActorMessage(m)
  }

  implicit val config = scraperConfig

  def schedule(): Unit = {
    // todo(ray): check overdue count
    val (assignedCount, assignedOverdues) = db.readOnly(attempts = 2, dbMasterSlave = Slave) { implicit s =>
      val assignedCount = scrapeInfoRepo.getAssignedCount()
      val assignedOverdues = scrapeInfoRepo.getOverdueAssignedList(currentDateTime.minusMinutes(config.pendingOverdueThreshold))
      (assignedCount, assignedOverdues)
    }
    log.info(s"[schedule]: assigned:${assignedCount} assigned-overdues=${assignedOverdues.length}")

    if (!assignedOverdues.isEmpty) {
      val workers = serviceDiscovery.serviceCluster(ServiceType.SCRAPER).allMembers
      if (workers.isEmpty) {
        airbrake.panic("[ScrapeScheduler] Scraper cluster is EMPTY!")
      } else {
        val workerIds = workers.map(_.id.id).toSet
        log.warn(s"[schedule] #assigned-overdues=${assignedOverdues.length}; active workerIds=${workerIds}; assigned-overdues=${assignedOverdues.mkString(",")}")
        val (assigned, orphaned) = assignedOverdues partition { info => info.workerId.isDefined }
        if (!orphaned.isEmpty) {
          val msg = s"[schedule] orphaned scraper tasks(${orphaned.length}): ${orphaned.mkString(",")}"
          log.error(msg) // shouldn't happen -- airbrake
          systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-orphaned", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- orphaned) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime))
            }
          }
        }

        val (stalled, abandoned) = assigned partition { info => workerIds.contains(info.workerId.get.id) }
        if (!abandoned.isEmpty) {
          val msg = s"[schedule] abandoned scraper tasks(${abandoned.length}): ${abandoned.mkString(",")}"
          log.warn(msg)
          systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-abandoned", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- abandoned) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime)) // todo(ray): ask worker for status
            }
          }
        }
        if (!stalled.isEmpty) { // likely due to failed db updates
          val msg = s"[schedule] stalled scraper tasks(${stalled.length}): ${stalled.mkString(",")}"
          log.warn(msg)
          systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-stalled", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- stalled) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime.plusMinutes(util.Random.nextInt(30)))) // todo(ray): ask worker for status
            }
          }
        }
      }
    }
  }

  def checkOverdues(): Unit = {
    val overdueCount = db.readOnly(attempts = 2, dbMasterSlave = Slave) { implicit s => scrapeInfoRepo.getOverdueCount() }
    val msg = s"[checkOverdues]: overdue-count=${overdueCount}"
    if (overdueCount > config.overdueCountThreshold) {
      log.warn(msg)
      systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.ENG, to = Seq(EmailAddresses.RAY, EmailAddresses.MARTIN), category = NotificationCategory.System.SCRAPER, subject = "scraper-many-overdues", htmlBody = msg))
    } else {
      log.info(msg)
    }
  }
}

class ScrapeSchedulerPluginImpl @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    systemAdminMailSender: SystemAdminMailSender,
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
    scheduleTaskOnLeader(actor.system, 30 seconds, scraperConfig.checkOverduesFrequency minutes, actor.ref, CheckOverdues)
  }
  override def onStop() {
    log.info(s"[onStop] ScrapeScheduler stopped")
    super.onStop()
  }

  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {
    require(uri != null && !uri.id.isEmpty, "[scheduleScrape] <uri> cannot be null and <uri.id> cannot be empty")
    val uriId = uri.id.get
    if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(uri.state)) {
      val info = scrapeInfoRepo.getByUriId(uriId)
      val toSave = info match {
        case Some(s) => s.state match {
          case ScrapeInfoStates.ACTIVE   => s.withNextScrape(date)
          case ScrapeInfoStates.PENDING | ScrapeInfoStates.ASSIGNED => s // no change
          case ScrapeInfoStates.INACTIVE => {
            val msg = s"[scheduleScrape($uri.url)] scheduling an INACTIVE ($s) for scraping"
            log.warn(msg)
            systemAdminMailSender.sendMail(ElectronicMail(from = EmailAddresses.RAY, to = List(EmailAddresses.RAY),
              subject = s"ScrapeScheduler.scheduleScrape($uri)",
              htmlBody = s"$msg\n${Thread.currentThread.getStackTrace.mkString("\n")}",
              category = NotificationCategory.System.ADMIN))
            s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(date) // dangerous; revisit
          }
        }
        case None => ScrapeInfo(uriId = uriId, nextScrape = date)
      }
      scrapeInfoRepo.save(toSave)
      // todo: It may be nice to force trigger a scrape directly
    }
  }

  def scrapeBasicArticle(url: String, extractorProviderType:Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    require(url != null, "[scrapeBasicArticle] <url> cannot be null")
    val proxyOpt = db.readOnly { implicit s =>
      urlPatternRuleRepo.getProxy(url)
    }
    log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getBasicArticle(url, proxyOpt, extractorProviderType)
  }

  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = {
    val proxyOpt = db.readOnly { implicit s => urlPatternRuleRepo.getProxy(url) }
    log.info(s"[getSignature] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getSignature(url, proxyOpt, extractorProviderType)
  }
}

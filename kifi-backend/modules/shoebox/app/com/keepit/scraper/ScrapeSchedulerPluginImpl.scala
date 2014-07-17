package com.keepit.scraper

import com.keepit.common.healthcheck.{ SystemAdminMailSender, AirbrakeNotifier }
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.common.net.URI
import com.keepit.model._
import scala.concurrent.Future
import akka.util.Timeout
import scala.concurrent.duration._
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.scraper.extractor.ExtractorProviderType
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.common.time._
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.common.service.ServiceType
import com.keepit.common.db.slick.Database.Replica
import org.joda.time.DateTime
import scala.util.Try

case object CheckOverdues
case object CheckOverdueCount

private[scraper] class ScrapeScheduler @Inject() (
    scraperConfig: ScraperSchedulerConfig,
    airbrake: AirbrakeNotifier,
    db: Database,
    serviceDiscovery: ServiceDiscovery,
    systemAdminMailSender: SystemAdminMailSender,
    scrapeInfoRepo: ScrapeInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    scraperServiceClient: ScraperServiceClient) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case CheckOverdues =>
      checkOverdues()
    case CheckOverdueCount =>
      checkOverdueCount()
    case m => throw new UnsupportedActorMessage(m)
  }

  implicit val config = scraperConfig

  def checkOverdues(): Unit = {
    val assignedOverdues = db.readOnlyReplica(attempts = 2) { implicit s =>
      scrapeInfoRepo.getAssignedList(due = currentDateTime.minusMinutes(config.pendingOverdueThreshold))
    }
    log.info(s"[checkOverdues]: assigned-overdues=${assignedOverdues.length}")

    if (!assignedOverdues.isEmpty) {
      val workers = serviceDiscovery.serviceCluster(ServiceType.SCRAPER).allMembers
      if (workers.isEmpty) {
        airbrake.panic("[ScrapeScheduler] Scraper cluster is EMPTY!")
      } else {
        val workerIds = workers.map(_.id.id).toSet
        log.warn(s"[checkOverdues] #assigned-overdues=${assignedOverdues.length}; active workerIds=${workerIds}; assigned-overdues=${assignedOverdues.mkString(",")}")
        val (assigned, orphaned) = assignedOverdues partition { info => info.workerId.isDefined }
        if (!orphaned.isEmpty) {
          val msg = s"[checkOverdues] orphaned scraper tasks(${orphaned.length}): ${orphaned.mkString(",")}"
          log.error(msg) // shouldn't happen -- airbrake
          systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = Seq(SystemEmailAddress.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-orphaned", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- orphaned) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime))
            }
          }
        }

        val (stalled, abandoned) = assigned partition { info => workerIds.contains(info.workerId.get.id) }
        if (!abandoned.isEmpty) {
          val msg = s"[checkOverdues] abandoned scraper tasks(${abandoned.length}): ${abandoned.mkString(",")}"
          log.warn(msg)
          systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = Seq(SystemEmailAddress.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-abandoned", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- abandoned) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime)) // todo(ray): ask worker for status
            }
          }
        }
        if (!stalled.isEmpty) { // likely due to failed db updates
          val msg = s"[checkOverdues] stalled scraper tasks(${stalled.length}): ${stalled.mkString(",")}"
          log.warn(msg)
          systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = Seq(SystemEmailAddress.RAY), category = NotificationCategory.System.SCRAPER, subject = "scraper-scheduler-stalled", htmlBody = msg))
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- stalled) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime.plusMinutes(util.Random.nextInt(30)))) // todo(ray): ask worker for status
            }
          }
        }
      }
    }
  }

  def checkOverdueCount(): Unit = {
    val overdueCount = db.readOnlyReplica(attempts = 2) { implicit s => scrapeInfoRepo.getOverdueCount() }
    val msg = s"[checkOverdueCount]: overdue-count=${overdueCount}"
    if (overdueCount > config.overdueCountThreshold) {
      log.warn(msg)
      systemAdminMailSender.sendMail(ElectronicMail(from = SystemEmailAddress.ENG, to = Seq(SystemEmailAddress.RAY, SystemEmailAddress.MARTIN), category = NotificationCategory.System.SCRAPER, subject = "scraper-many-overdues", htmlBody = msg))
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
  scraperConfig: ScraperSchedulerConfig,
  scraperClient: ScraperServiceClient,
  val scheduling: SchedulingProperties) //only on leader
    extends ScrapeSchedulerPlugin with SchedulerPlugin with Logging {

  implicit val actorTimeout = Timeout(scraperConfig.actorTimeout)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info(s"[onStart] starting ScraperPluginImpl with scraperConfig=$scraperConfig}")
    scheduleTaskOnLeader(actor.system, 30 seconds, scraperConfig.scrapePendingFrequency seconds, actor.ref, CheckOverdues)
    scheduleTaskOnLeader(actor.system, 30 seconds, scraperConfig.checkOverdueCountFrequency minutes, actor.ref, CheckOverdueCount)
  }

  def scheduleScrape(uri: NormalizedURI, date: DateTime)(implicit session: RWSession): Unit = {
    require(uri != null && !uri.id.isEmpty, "[scheduleScrape] <uri> cannot be null and <uri.id> cannot be empty")
    val uriId = uri.id.get
    if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(uri.state)) {
      val info = scrapeInfoRepo.getByUriId(uriId)
      val toSave = info match {
        case Some(s) => s.state match {
          case ScrapeInfoStates.ACTIVE => s.withNextScrape(date)
          case ScrapeInfoStates.ASSIGNED => s // no change
          case ScrapeInfoStates.INACTIVE => {
            log.warn(s"[scheduleScrape(${uri.toShortString})] scheduling INACTIVE $s")
            s.withState(ScrapeInfoStates.ACTIVE).withNextScrape(date) // dangerous; revisit
          }
        }
        case None => ScrapeInfo(uriId = uriId, nextScrape = date)
      }
      scrapeInfoRepo.save(toSave)
      // todo: It may be nice to force trigger a scrape directly
    }
  }

  @inline private def sanityCheck(url: String): Unit = {
    require(url != null, "<url> cannot be null")
    val parseUriTr = Try(java.net.URI.create(url)) // java.net.URI needed for current impl of HttpFetcher
    require(parseUriTr.isSuccess, s"java.net.URI parser failed to parse url=($url) error=${parseUriTr.failed.get}")
  }

  @inline private def getProxy(url: String): Option[HttpProxy] = db.readOnlyMaster { implicit s => urlPatternRuleRepo.getProxy(url) } // cached; use master

  def scrapeBasicArticle(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[BasicArticle]] = {
    sanityCheck(url)
    val proxyOpt = getProxy(url)
    log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getBasicArticle(url, proxyOpt, extractorProviderType)
  }

  def getSignature(url: String, extractorProviderType: Option[ExtractorProviderType]): Future[Option[Signature]] = {
    sanityCheck(url)
    val proxyOpt = getProxy(url)
    log.info(s"[getSignature] invoke (remote) Scraper service; url=$url proxy=$proxyOpt extractorProviderType=$extractorProviderType")
    scraperClient.getSignature(url, proxyOpt, extractorProviderType)
  }
}

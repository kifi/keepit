package com.keepit.scraper

import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }
import com.keepit.common.db.slick.Database
import com.keepit.common.healthcheck.{ SystemAdminMailSender, AirbrakeNotifier }
import com.keepit.common.logging.Logging
import com.keepit.common.mail.{ SystemEmailAddress, ElectronicMail }
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.service.ServiceType
import com.keepit.common.time._
import com.keepit.common.zookeeper.ServiceDiscovery
import com.keepit.model._
import scala.concurrent.duration._

import scala.concurrent.duration.FiniteDuration

case object CheckOverdues
case object CheckOverdueCount

private[scraper] class ScraperHealthMonitor @Inject() (
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
          airbrake.notify(msg)
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
          airbrake.notify(msg)
          db.readWrite(attempts = 2) { implicit rw =>
            for (info <- abandoned) {
              scrapeInfoRepo.save(info.withState(ScrapeInfoStates.ACTIVE).withNextScrape(currentDateTime)) // todo(ray): ask worker for status
            }
          }
        }
        if (!stalled.isEmpty) { // likely due to failed db updates
          val msg = s"[checkOverdues] stalled scraper tasks(${stalled.length}): ${stalled.mkString(",")}"
          log.warn(msg)
          airbrake.notify(msg)
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
      airbrake.notify(msg)
    } else {
      log.info(msg)
    }
  }
}

trait ScraperHealthMonitorPlugin extends SchedulerPlugin

class ScraperHealthMonitorPluginImpl @Inject() (
    val actor: ActorInstance[ScraperHealthMonitor],
    scraperConfig: ScraperSchedulerConfig,
    val scheduling: SchedulingProperties) extends ScraperHealthMonitorPlugin {

  val name: String = getClass.toString

  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true

  val interval: FiniteDuration = 5 seconds

/*  override def onStart() {
    log.info(s"[onStart] starting ScraperHealthMonitorPlugin with scraperConfig=$scraperConfig}")
    scheduleTaskOnOneMachine(actor.system, 6 minutes, scraperConfig.scrapePendingFrequency seconds, actor.ref, CheckOverdues, CheckOverdues.getClass.getSimpleName)
    scheduleTaskOnOneMachine(actor.system, 7 minutes, scraperConfig.checkOverdueCountFrequency minutes, actor.ref, CheckOverdueCount, CheckOverdueCount.getClass.getSimpleName)
  }*/
}

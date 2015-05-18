package com.keepit.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.common.net.URI
import com.keepit.integrity.UriIntegrityHelpers
import com.keepit.model._
import com.keepit.scraper.{ ScraperSchedulerConfig, ScrapeRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ timing, timingWithResult }
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import com.keepit.common.core._

import scala.concurrent.Future

@Singleton
class ScraperCallbackHelper @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    urlPatternRules: UrlPatternRulesCommander,
    normUriRepo: NormalizedURIRepo,
    scrapeInfoRepo: ScrapeInfoRepo,
    implicit val scraperConfig: ScraperSchedulerConfig,
    integrityHelpers: UriIntegrityHelpers) extends Logging {

  val assignLock = new ReactiveLock(1, Some(30))

  private[this] var averageNumberOfTasks = 0.0 // an exponential moving average of the number of tasks assigned to a scraper instance
  private[this] val alpha = 0.3
  private[this] val growth = 2

  def assignTasks(zkId: Id[ScraperWorker], max: Int): Future[Seq[ScrapeRequest]] = timing(s"assignTasks($zkId,$max)") {
    val targetNumOfTasks = math.min(max, averageNumberOfTasks.toInt + growth)
    val rules = db.readOnlyMaster { implicit s => urlPatternRules.rules() }
    val requests: Future[Seq[ScrapeRequest]] = assignLock.withLock {
      val res = db.readWrite(attempts = 1) { implicit rw =>
        val builder = Seq.newBuilder[ScrapeRequest]
        val limit = if (targetNumOfTasks < 10) targetNumOfTasks * 2 else targetNumOfTasks
        val overdues = timingWithResult[Seq[ScrapeInfo]](s"assignTasks($zkId,$max) getOverdueList($limit)", { r: Seq[ScrapeInfo] => s"${r.length} overdues: ${r.map(_.toShortString).mkString(",")}" }) {
          scrapeInfoRepo.getOverdueList(limit)
        }
        var count = 0
        for (info <- overdues if count < targetNumOfTasks) {
          val nuri = normUriRepo.get(info.uriId)
          if (URI.parse(nuri.url).isFailure) {
            scrapeInfoRepo.save(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
            airbrake.notify(s"can't parse $nuri, not passing it to the scraper, marking as unscrapable")
          } else if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(nuri.state)) { // todo(ray): batch
            if (rules.isUnscrapable(nuri.url)) {
              val saved = scrapeInfoRepo.save(info.withDocumentUnchanged()) // revisit later
              log.warn(s"[assignTasks($zkId,$max)] ${nuri.url} is considered unscrapable; skipped for now. savedInfo=$saved; uri=${nuri.toShortString}")
            } else {
              val proxy = urlPatternRules.getProxy(nuri.url)
              val savedInfo = scrapeInfoRepo.save(info.withWorkerId(zkId).withState(ScrapeInfoStates.ASSIGNED))
              log.debug(s"[assignTasks($zkId,$max)] #$count assigned (${nuri.id.get},${savedInfo.id.get},${nuri.url}) to worker $zkId")
              count += 1
              builder += ScrapeRequest(nuri, savedInfo, proxy)
            }
          } else {
            val saved = scrapeInfoRepo.save(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
            log.warn(s"[assignTasks($zkId,$max)] ${nuri.state} in DO_NOT_SCRAPE list; uri=$nuri; deactivated scrapeInfo=$saved")
          }
        }
        builder.result()
      }
      if (res.length == 0) {
        log.warn(s"[assignTask($zkId,$max)] 0 tasks assigned") // can be more aggressive
      }
      val limit = res.take(max)
      statsd.gauge("scraper.assign", limit.length)
      synchronized { averageNumberOfTasks = (alpha * limit.length.toDouble) + ((1 - alpha) * averageNumberOfTasks) }
      limit
    }
    requests
  }

  def saveNormalizedURI(normalizedUri: NormalizedURI): NormalizedURI = {
    log.info(s"scraper callback: save uri: ${normalizedUri.id.get}")
    statsd.gauge("scraper.saveNormalizedURI", 1)
    db.readWrite(attempts = 1) { implicit s =>
      normUriRepo.save(normalizedUri) tap integrityHelpers.improveKeepsSafely
    }
  }
}

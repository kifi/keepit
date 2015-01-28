package com.keepit.controllers.internal

import com.google.inject.{ Inject, Singleton }
import com.keepit.common.concurrent.ReactiveLock
import com.keepit.model._
import com.keepit.scraper.{ ScraperSchedulerConfig, ScrapeRequest }
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{ timing, timingWithResult }
import com.keepit.common.healthcheck.AirbrakeNotifier
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.concurrent.Future

@Singleton
class ScraperCallbackHelper @Inject() (
    db: Database,
    airbrake: AirbrakeNotifier,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    normUriRepo: NormalizedURIRepo,
    pageInfoRepo: PageInfoRepo,
    imageInfoRepo: ImageInfoRepo,
    scrapeInfoRepo: ScrapeInfoRepo,
    implicit val scraperConfig: ScraperSchedulerConfig) extends Logging {

  private val assignLock = new ReactiveLock(1)
  private val pageInfoLock = new ReactiveLock(1)
  private val imageInfoLock = new ReactiveLock(1)

  def assignTasks(zkId: Id[ScraperWorker], max: Int): Future[Seq[ScrapeRequest]] = timing(s"assignTasks($zkId,$max)") {
    val rules = urlPatternRuleRepo.rules()
    val requests: Future[Seq[ScrapeRequest]] = assignLock.withLock {
      val res = db.readWrite(attempts = 1) { implicit rw =>
        val builder = Seq.newBuilder[ScrapeRequest]
        val limit = if (max < 10) max * 2 else max
        val overdues = timingWithResult[Seq[ScrapeInfo]](s"assignTasks($zkId,$max) getOverdueList(${limit})", { r: Seq[ScrapeInfo] => s"${r.length} overdues: ${r.map(_.toShortString).mkString(",")}" }) { scrapeInfoRepo.getOverdueList(limit) }
        var count = 0
        for (info <- overdues if count < max) {
          val nuri = normUriRepo.get(info.uriId)
          if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(nuri.state)) { // todo(ray): batch
            if (rules.isUnscrapable(nuri.url)) {
              val saved = scrapeInfoRepo.save(info.withDocumentUnchanged()) // revisit later
              log.warn(s"[assignTasks($zkId,$max)] ${nuri.url} is considered unscrapable; skipped for now. savedInfo=$saved; uri=${nuri.toShortString}")
            } else {
              val pageInfoOpt = pageInfoRepo.getByUri(nuri.id.get)
              val proxy = urlPatternRuleRepo.getProxy(nuri.url)
              val savedInfo = scrapeInfoRepo.save(info.withWorkerId(zkId).withState(ScrapeInfoStates.ASSIGNED))
              log.debug(s"[assignTasks($zkId,$max)] #${count} assigned (${nuri.id.get},${savedInfo.id.get},${nuri.url}) to worker $zkId")
              count += 1
              builder += ScrapeRequest(nuri, savedInfo, pageInfoOpt, proxy)
            }
          } else {
            val saved = scrapeInfoRepo.save(info.withStateAndNextScrape(ScrapeInfoStates.INACTIVE))
            log.warn(s"[assignTasks($zkId,$max)] ${nuri.state} in DO_NOT_SCRAPE list; uri=$nuri; deactivated scrapeInfo=$saved")
          }
        }
        builder.result
      }
      if (res.length == 0) {
        log.warn(s"[assignTask($zkId,$max)] 0 tasks assigned") // can be more aggressive
      }
      val limit = res.take(max)
      statsd.gauge("scraper.assign", limit.length)
      limit
    }
    requests
  }

  def saveImageInfo(info: ImageInfo): Unit = {
    imageInfoLock.withLock {
      db.readWrite(attempts = 3) { implicit s =>
        imageInfoRepo.save(info)
      }
    }
  }

  def savePageInfo(info: PageInfo): Future[PageInfo] = {
    pageInfoLock.withLock {
      db.readWrite(attempts = 3) { implicit s =>
        try {
          pageInfoRepo.save(info)
        } catch {
          case e: Exception => //typically MySQLIntegrityConstraintViolationException but any may do here
            pageInfoRepo.getByUri(info.uriId) match {
              case Some(fromDb) =>
                //race condition. we lost, lets override...
                pageInfoRepo.save(info.copy(id = fromDb.id))
              case None =>
                throw e
            }
        }
      }
    }
  }

  def saveNormalizedURI(normalizedUri: NormalizedURI): NormalizedURI = {
    log.info(s"scraper callback: save uri: ${normalizedUri.id.get}")
    db.readWrite(attempts = 1) { implicit s =>
      normUriRepo.save(normalizedUri)
    }
  }
}

package com.keepit.controllers.internal

import com.google.inject.{Inject, Singleton}
import com.keepit.model._
import com.keepit.scraper.ScrapeRequest
import com.keepit.common.db.Id
import com.keepit.common.db.slick.Database
import com.keepit.common.logging.Logging
import com.keepit.common.performance.{timing,timingWithResult}
import java.util.concurrent.locks.ReentrantLock
import com.keepit.common.healthcheck.AirbrakeNotifier


@Singleton
class ScraperCallbackHelper @Inject()(
  db:Database,
  airbrake:AirbrakeNotifier,
  urlPatternRuleRepo:UrlPatternRuleRepo,
  normUriRepo:NormalizedURIRepo,
  pageInfoRepo:PageInfoRepo,
  imageInfoRepo:ImageInfoRepo,
  scrapeInfoRepo:ScrapeInfoRepo
  ) extends Logging {

  private val assignLock    = new ReentrantLock()
  private val pageInfoLock  = new ReentrantLock()
  private val imageInfoLock = new ReentrantLock()
  private val normalizedUriLock = new ReentrantLock()

  def withLock[T](lock:ReentrantLock)(f: => T) = {
    try {
      lock.lock
      f
    } finally {
      lock.unlock
    }
  }

  def assignTasks(zkId:Id[ScraperWorker], max:Int):Seq[ScrapeRequest] = timingWithResult(s"assignTasks($zkId,$max)", {r:Seq[ScrapeRequest] => s"${r.length} uris assigned: ${r.mkString(",")}"}) {
    withLock(assignLock) {
      val builder = Seq.newBuilder[ScrapeRequest]
      val res = db.readWrite(attempts = 1) { implicit rw =>
        val limit = if (max < 10) max * 2 else max
        val overdues = timingWithResult[Seq[ScrapeInfo]](s"assignTasks($zkId,$max) getOverdueList(${limit})", {r:Seq[ScrapeInfo] => s"${r.length} overdues: ${r.map(_.toShortString).mkString(",")}"}) { scrapeInfoRepo.getOverdueList(limit) }
        var count = 0
        for (info <- overdues if count < max) {
          val nuri = normUriRepo.get(info.uriId)
          if (!NormalizedURIStates.DO_NOT_SCRAPE.contains(nuri.state)) { // todo(ray): batch
            val pageInfoOpt = pageInfoRepo.getByUri(nuri.id.get)
            val proxy = urlPatternRuleRepo.getProxy(nuri.url)
            val savedInfo = scrapeInfoRepo.save(info.withWorkerId(zkId).withState(ScrapeInfoStates.ASSIGNED))
            log.debug(s"[assignTasks($zkId,$max)] #${count} assigned (${nuri.id.get},${savedInfo.id.get},${nuri.url}) to worker $zkId")
            count += 1
            builder += ScrapeRequest(nuri, savedInfo, pageInfoOpt, proxy)
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
  }

  def saveImageInfo(info:ImageInfo):ImageInfo = {
    withLock(imageInfoLock) {
      db.readWrite(attempts = 3) { implicit s =>
        imageInfoRepo.save(info)
      }
    }
  }

  def savePageInfo(info:PageInfo) = {
    withLock(pageInfoLock) {
      db.readWrite(attempts = 3) { implicit s =>
        pageInfoRepo.save(info)
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

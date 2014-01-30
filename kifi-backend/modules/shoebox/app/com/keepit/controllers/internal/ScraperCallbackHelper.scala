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
  scrapeInfoRepo:ScrapeInfoRepo
  ) extends Logging {

  private val lock = new ReentrantLock()

  def withLock[T](lock:ReentrantLock)(f: => T) = {
    try {
      lock.lock
      f
    } finally {
      lock.unlock
    }
  }

  def assignTasks(zkId:Id[ScraperWorker], max:Int):Seq[ScrapeRequest] = timingWithResult[Seq[ScrapeRequest]](s"assignTasks($zkId,$max)", {_.mkString(",")}) {
    withLock(lock) {
      val res = db.readWrite(attempts = 2) { implicit rw =>
        scrapeInfoRepo.getOverdueList(max) map { info => // todo: prioritize
          val nuri = normUriRepo.get(info.uriId)
          val proxy = urlPatternRuleRepo.getProxy(nuri.url)
          val savedInfo = scrapeInfoRepo.save(info.withWorkerId(zkId).withState(ScrapeInfoStates.ASSIGNED))
          ScrapeRequest(nuri, savedInfo, proxy)
        }
      }
      res
    }
  }

}

package com.keepit.scraper

import com.keepit.common.logging.Logging
import com.google.inject._
import com.keepit.common.db.slick._
import com.keepit.common.time._
import com.keepit.search.ArticleStore
import com.keepit.model._
import com.keepit.scraper.extractor.ExtractorFactory
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.store.S3ScreenshotStore
import com.keepit.model.ScrapeInfo
import com.keepit.search.Article
import com.keepit.normalizer.NormalizationService
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.collection.mutable
import play.modules.statsd.api.Statsd

@Singleton
class Scraper @Inject() (
  db: Database,
  articleStore: ArticleStore,
  extractorFactory: ExtractorFactory,
  scraperConfig: ScraperConfig,
  scrapeInfoRepo: ScrapeInfoRepo,
  normalizedURIRepo: NormalizedURIRepo,
  airbrake: AirbrakeNotifier,
  bookmarkRepo: BookmarkRepo,
  urlPatternRuleRepo: UrlPatternRuleRepo,
  s3ScreenshotStore: S3ScreenshotStore,
  normalizationServiceProvider: Provider[NormalizationService],
  scraperServiceClient:ScraperServiceClient
) extends Logging {

  implicit val config = scraperConfig

  def run(): Seq[(NormalizedURI, Option[Article])] = {
    val startedTime = currentDateTime
    log.info("[run] starting a new scrape round")
    val tasks = db.readOnly { implicit s =>
      scrapeInfoRepo.getOverdueList().map{ info => (normalizedURIRepo.get(info.uriId), info) }
    }
    log.info("[run] got %s uris to scrape".format(tasks.length))

    log.info(s"[run] invoke (remote) Scraper service; uris(len=${tasks.length}) $tasks")
    val buf = new mutable.ArrayBuffer[(NormalizedURI, Option[Article])]
    tasks.grouped(scraperConfig.batchSize).foreach { g =>  // revisit rate-limit
      val futures = g.map { case (uri, info) =>
        scraperServiceClient.asyncScrapeWithInfo(uri, info)
      }
      val res:Seq[(NormalizedURI, Option[Article])] = futures.map(f => Await.result(f, 10 seconds)) // revisit
      log.info(s"[run] (remote) results=$res")
      buf ++= res
      res
    }
    buf.toSeq
  }

  def schedule(): Unit = {
    log.info("[schedule] starting a new scrape round")
    val (activeOverdues, pendingCount, pendingOverdues) = db.readOnly { implicit s =>
      (scrapeInfoRepo.getOverdueList(), scrapeInfoRepo.getPendingCount(), scrapeInfoRepo.getOverduePendingList(currentDateTime.minusSeconds(config.pendingOverdueThreshold)))
    }
    log.info(s"[schedule-active]:  (len=${activeOverdues.length}) ${activeOverdues.map(i => (i.id, i.destinationUrl)).mkString(System.lineSeparator)}")
    log.info(s"[schedule-pending]: pendingCount=${pendingCount} overdues: (len=${pendingOverdues.length}) ${pendingOverdues.map(i => (i.id, i.destinationUrl)).mkString(System.lineSeparator)}")

    val batchMax = scraperConfig.batchMax
    val pendingSkipThreshold = scraperConfig.pendingSkipThreshold // TODO: adjust dynamically
    val adjPendingCount = (pendingCount - pendingOverdues.length) // assuming overdue ones are no longer being worked on
    val infos = if (adjPendingCount > pendingSkipThreshold) {
      log.warn(s"[schedule] # of pending jobs (adj=${adjPendingCount}, pending=${pendingCount}, pendingOverdues=${pendingOverdues.length}) > $pendingSkipThreshold. Skip a round.")
      Seq.empty[ScrapeInfo]
    } else {
      activeOverdues.take(batchMax) ++ pendingOverdues.take(batchMax)
    }

    val tasks = if (infos.isEmpty) {
      Seq.empty[(NormalizedURI, ScrapeInfo)]
    } else db.readOnly { implicit s =>
      infos.map{ info => (normalizedURIRepo.get(info.uriId), info) }
    }
    log.info("[schedule] got %s uris to scrape".format(tasks.length))
    Statsd.gauge("scraper.scheduler.uris.count", tasks.length)
    val ts = System.currentTimeMillis
    tasks.grouped(scraperConfig.batchSize).foreach { g => // revisit rate-limit
      val futures = g map { case (uri, info) =>
        db.readWrite { implicit s =>
          val savedInfo = scrapeInfoRepo.save(info.withState(ScrapeInfoStates.PENDING)) // TODO: batch
        val proxyOpt = urlPatternRuleRepo.getProxy(uri.url)
          ScrapeRequest(uri, savedInfo, proxyOpt)
        }
      } map { case sr =>
        scraperServiceClient.scheduleScrapeWithRequest(sr)
      }
      Await.result(Future sequence (futures), 5 seconds) // todo: remove arbitrary await
      log.info(s"[schedule-WithRequest] (remote) finished scheduling batch (sz=${g.length}) ${g.map(_._1.url).mkString}") // todo: ScheduleResult
    }
    log.info(s"[schedule-WithRequest] finished scheduling ${tasks.length} uris for scraping. time-lapsed:${System.currentTimeMillis - ts}")
  }

}

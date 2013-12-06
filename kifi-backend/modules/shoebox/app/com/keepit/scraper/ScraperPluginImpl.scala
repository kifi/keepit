package com.keepit.scraper

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model._
import scala.concurrent.{Await, Future}
import akka.util.Timeout
import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.scraper.extractor.{ExtractorFactory, ExtractorProviderType}
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession
import com.keepit.search.ArticleStore
import com.keepit.common.time._
import play.modules.statsd.api.Statsd
import play.api.libs.concurrent.Execution.Implicits.defaultContext

case object Scrape

private[scraper] class ScrapeScheduler @Inject() (
    scraperConfig: ScraperConfig,
    airbrake: AirbrakeNotifier,
    db: Database,
    articleStore: ArticleStore,
    scrapeInfoRepo: ScrapeInfoRepo,
    normalizedURIRepo: NormalizedURIRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    scraperServiceClient:ScraperServiceClient
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Scrape => schedule()
    case m => throw new UnsupportedActorMessage(m)
  }

  implicit val config = scraperConfig

  def schedule(): Unit = {
    log.info("[schedule] starting a new scrape round")
    val (activeOverdues, pendingCount, pendingOverdues) = db.readOnly { implicit s =>
      (scrapeInfoRepo.getOverdueList(), scrapeInfoRepo.getPendingCount(), scrapeInfoRepo.getOverduePendingList(currentDateTime.minusSeconds(config.pendingOverdueThreshold)))
    }
    log.info(s"[schedule-active]:  (len=${activeOverdues.length}) ${activeOverdues.map(i => (i.id, i.destinationUrl)).mkString(System.lineSeparator)}")
    log.info(s"[schedule-pending]: pendingCount=${pendingCount} overdues: (len=${pendingOverdues.length}) ${pendingOverdues.map(i => (i.id, i.destinationUrl)).mkString(System.lineSeparator)}")

    val batchMax = scraperConfig.batchMax
    val pendingSkipThreshold = scraperConfig.pendingSkipThreshold // todo: adjust dynamically
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
          val savedInfo = scrapeInfoRepo.save(info.withState(ScrapeInfoStates.PENDING)) // todo: batch
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

class ScraperPluginImpl @Inject() (
    db: Database,
    scrapeInfoRepo: ScrapeInfoRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    actor: ActorInstance[ScrapeScheduler],
    scraperConfig: ScraperConfig,
    scraperClient: ScraperServiceClient,
    val schedulingProperties: SchedulingProperties) //only on leader
  extends ScraperPlugin with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(scraperConfig.actorTimeout)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info(s"[onStart] starting ScraperPluginImpl with scraperConfig=$scraperConfig}")
    scheduleTask(actor.system, 30 seconds, scraperConfig.scrapePendingFrequency seconds, actor.ref, Scrape)
  }

  def scheduleScrape(uri: NormalizedURI)(implicit session: RWSession): Unit = {
    require(uri != null && !uri.id.isEmpty, "[scheduleScrape] <uri> cannot be null and <uri.id> cannot be empty")
    val uriId = uri.id.get
    val info = scrapeInfoRepo.getByUriId(uriId)
    val toSave = info match {
      case Some(s) => s.withState(ScrapeInfoStates.PENDING)
      case None => ScrapeInfo(uriId = uriId, state = ScrapeInfoStates.PENDING)
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

package com.keepit.scraper

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model._

import akka.actor._
import scala.concurrent.Future
import akka.util.Timeout

import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.scraper.extractor.{ExtractorProviderType}
import com.keepit.common.db.slick.Database
import com.keepit.common.db.slick.DBSession.RWSession

case object Scrape

private[scraper] class ScraperActor @Inject() (
    scraper: Scraper,
    scraperConfig: ScraperConfig,
    airbrake: AirbrakeNotifier)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Scrape => {
      log.info("Starting scraping session")
      sender ! scraper.schedule()
    }
    case m => throw new UnsupportedActorMessage(m)
  }
}

class ScraperPluginImpl @Inject() (
    db: Database,
    scrapeInfoRepo: ScrapeInfoRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    actor: ActorInstance[ScraperActor],
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

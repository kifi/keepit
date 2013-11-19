package com.keepit.scraper

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model._
import com.keepit.search.Article

import akka.actor._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._

import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.scraper.extractor.Extractor
import com.keepit.common.db.slick.Database

case object Scrape
case class ScrapeInstance(uri: NormalizedURI)
case class ScrapeBasicArticle(url: String, customExtractor: Option[Extractor])

private[scraper] class ScraperActor @Inject() (
    scraper: Scraper,
    scraperConfig: ScraperConfig,
    airbrake: AirbrakeNotifier)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Scrape => {
      log.info("Starting scraping session")
      if (scraperConfig.disableScraperService) {
        sender ! scraper.run()
      } else {
        sender ! scraper.schedule()
      }
    }
    case ScrapeInstance(uri) => sender ! scraper.safeProcessURI(uri)
    case m => throw new UnsupportedActorMessage(m)
  }
}

private[scraper] class ReadOnlyScraperActor @Inject() (
  scraper: Scraper,
  airbrake: AirbrakeNotifier
) extends FortyTwoActor(airbrake) with Logging {
  def receive() = {
    case ScrapeBasicArticle(url, customExtractor) => sender ! scraper.getBasicArticle(url, customExtractor)
    case m => throw new UnsupportedActorMessage(m)
  }
}

class ScraperPluginImpl @Inject() (
    db: Database,
    scrapeInfoRepo: ScrapeInfoRepo,
    urlPatternRuleRepo: UrlPatternRuleRepo,
    actor: ActorInstance[ScraperActor],
    readOnlyActor: ActorInstance[ReadOnlyScraperActor],
    scraper: Scraper,
    scraperConfig: ScraperConfig,
    scraperClient: ScraperServiceClient,
    val schedulingProperties: SchedulingProperties) //only on leader
  extends ScraperPlugin with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ScraperPluginImpl")
    scheduleTask(actor.system, 30 seconds, 1 minutes, actor.ref, Scrape)
  }
  override def onStop() {
    log.info("stopping ScraperPluginImpl")
    scraper.close()
  }

  override def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]] =
    actor.ref.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]

  def scheduleScrape(uri: NormalizedURI): Unit = {
    if (scraperConfig.disableScraperService) {
      actor.ref.ask(ScrapeInstance(uri))(1 minutes).mapTo[(NormalizedURI, Option[Article])]
    } else {
      val uriId = uri.id.get
      val (info, proxyOpt) = db.readOnly { implicit s =>
        val info = scrapeInfoRepo.getByUriId(uriId)
        val proxyOpt = urlPatternRuleRepo.getProxy(uri.url)
        (info, proxyOpt)
      }
      val toSave = info match {
        case Some(s) => s.withState(ScrapeInfoStates.PENDING)
        case None => ScrapeInfo(uriId = uriId, state = ScrapeInfoStates.PENDING)
      }
      val saved = db.readWrite { implicit s =>
        scrapeInfoRepo.save(toSave)
      }
      log.info(s"[scheduleScrape-WithRequest] invoke (remote) Scraper service; uri=(${uri.id}, ${uri.state}, ${uri.url}, proxy=$proxyOpt)")
      val f = scraperClient.scheduleScrapeWithRequest(ScrapeRequest(uri, saved, proxyOpt))
      Await.result(f, 5 seconds) // should be really quick
    }
  }

  override def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]] = {
    if (scraperConfig.disableScraperService || customExtractor.isDefined) {
      readOnlyActor.ref.ask(ScrapeBasicArticle(url, customExtractor))(1 minutes).mapTo[Option[BasicArticle]]
    } else {
      val proxyOpt = db.readOnly { implicit s =>
        urlPatternRuleRepo.getProxy(url)
      }
      log.info(s"[scrapeBasicArticle] invoke (remote) Scraper service; url=$url proxy=$proxyOpt")
      scraperClient.getBasicArticleP(url, proxyOpt)
    }
  }
}

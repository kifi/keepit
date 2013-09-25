package com.keepit.scraper

import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.actor.ActorInstance
import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.search.Article

import akka.actor._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Plugin

import scala.concurrent.duration._
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped
import com.keepit.scraper.extractor.Extractor

case object Scrape
case class ScrapeInstance(uri: NormalizedURI)
case class ScrapeBasicArticle(url: String, customExtractor: Option[Extractor])

private[scraper] class ScraperActor @Inject() (
    scraper: Scraper,
    airbrake: AirbrakeNotifier)
  extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Scrape =>
      log.info("Starting scraping session")
      sender ! scraper.run()
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

trait ScraperPlugin extends Plugin {
  def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]]
  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])]
  def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]]
}

class ScraperPluginImpl @Inject() (
    actor: ActorInstance[ScraperActor],
    readOnlyActor: ActorInstance[ReadOnlyScraperActor],
    scraper: Scraper,
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

  override def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] =
    actor.ref.ask(ScrapeInstance(uri))(1 minutes).mapTo[(NormalizedURI, Option[Article])]

  override def scrapeBasicArticle(url: String, customExtractor: Option[Extractor] = None): Future[Option[BasicArticle]] =
    readOnlyActor.ref.ask(ScrapeBasicArticle(url, customExtractor))(1 minutes).mapTo[Option[BasicArticle]]
}

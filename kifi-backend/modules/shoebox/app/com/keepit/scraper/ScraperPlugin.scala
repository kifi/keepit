package com.keepit.scraper

import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorProvider
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
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import net.codingwell.scalaguice.ScalaModule
import com.keepit.inject.AppScoped

case object Scrape
case class ScrapeInstance(uri: NormalizedURI)

private[scraper] class ScraperActor @Inject() (
    scraper: Scraper,
    healthcheckPlugin: HealthcheckPlugin)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case Scrape =>
      log.info("Starting scraping session")
      sender ! scraper.run()
    case ScrapeInstance(uri) => sender ! scraper.safeProcessURI(uri)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ScraperPlugin extends Plugin {
  def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]]
  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])]
}

class ScraperPluginImpl @Inject() (
    actorProvider: ActorProvider[ScraperActor],
    scraper: Scraper,
    val schedulingProperties: SchedulingProperties) //only on leader
  extends ScraperPlugin with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ScraperPluginImpl")
    scheduleTask(actorProvider.system, 30 seconds, 1 minutes, actorProvider.actor, Scrape)
  }
  override def onStop() {
    log.info("stopping ScraperPluginImpl")
    scraper.close()
  }

  override def scrapePending(): Future[Seq[(NormalizedURI, Option[Article])]] =
    actorProvider.actor.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]

  override def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = 
    actorProvider.actor.ask(ScrapeInstance(uri))(1 minutes).mapTo[(NormalizedURI, Option[Article])]
}

package com.keepit.scraper

import com.keepit.common.healthcheck.HealthcheckPlugin
import com.keepit.common.actor.ActorFactory
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

case object Scrape
case class ScrapeInstance(uri: NormalizedURI)

private[scraper] class ScraperActor @Inject() (
    scraper: Scraper,
    healthcheckPlugin: HealthcheckPlugin)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case Scrape => sender ! scraper.run()
    case ScrapeInstance(uri) => sender ! scraper.safeProcessURI(uri)
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ScraperPlugin extends Plugin {
  def scrape(): Seq[(NormalizedURI, Option[Article])]
  def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])]
}

class ScraperPluginImpl @Inject() (
    actorFactory: ActorFactory[ScraperActor],
    scraper: Scraper,
    val schedulingProperties: SchedulingProperties)
  extends ScraperPlugin with SchedulingPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ScraperPluginImpl")
    scheduleTask(actorFactory.system, 0 seconds, 1 minutes, actor, Scrape)
  }
  override def onStop() {
    log.info("stopping ScraperPluginImpl")
    scraper.close()
  }

  override def scrape(): Seq[(NormalizedURI, Option[Article])] = {
    val future = actor.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]
    Await.result(future, 1 minutes)
  }

  override def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    actor.ask(ScrapeInstance(uri))(1 minutes).mapTo[(NormalizedURI, Option[Article])]
  }
}

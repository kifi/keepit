package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.logging.Logging
import com.keepit.model.NormalizedURI
import com.keepit.search.Article

import akka.actor._
import akka.dispatch.Await
import akka.dispatch.Future
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import play.api.Plugin
import com.keepit.common.akka.FortyTwoActor

case object Scrape
case class ScrapeInstance(uri: NormalizedURI)

private[scraper] class ScraperActor(scraper: Scraper) extends FortyTwoActor with Logging {
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

class ScraperPluginImpl @Inject() (system: ActorSystem, scraper: Scraper) extends ScraperPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new ScraperActor(scraper) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting ScraperPluginImpl")
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 1 minutes, actor, Scrape)
    )
  }
  override def onStop(): Unit = {
    log.info("stopping ScraperPluginImpl")
    scraper.close()
    _cancellables.map(_.cancel)
  }

  override def scrape(): Seq[(NormalizedURI, Option[Article])] = {
    val future = actor.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]
    Await.result(future, 1 minutes)
  }

  override def asyncScrape(uri: NormalizedURI): Future[(NormalizedURI, Option[Article])] = {
    actor.ask(ScrapeInstance(uri))(1 minutes).mapTo[(NormalizedURI, Option[Article])]
  }
}

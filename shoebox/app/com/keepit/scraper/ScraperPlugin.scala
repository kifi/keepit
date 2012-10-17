package com.keepit.scraper

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
import com.keepit.search.Article
import com.keepit.model.NormalizedURI
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import akka.util.duration._
import akka.pattern.ask
import akka.dispatch.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import akka.dispatch.Future
import com.google.inject.Inject
import com.google.inject.Provider
import scala.collection.mutable.{Map => MutableMap}

case object Scrape

private[scraper] class ScraperActor(scraper: Scraper) extends Actor with Logging {
  
  def receive() = {
    case Scrape => sender ! scraper.run()
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ScraperPlugin extends Plugin {
  def scrape(): Seq[(NormalizedURI, Option[Article])]
}

class ScraperPluginImpl @Inject() (system: ActorSystem, scraper: Scraper) extends ScraperPlugin with Logging {
  
  implicit val actorTimeout = Timeout(5 seconds)
  
  private val actor = system.actorOf(Props { new ScraperActor(scraper) })
  
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting ArticleIndexerPluginImpl")
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 1 minutes, actor, Scrape)
    )
  }
  override def onStop(): Unit = {
    log.info("stopping ArticleIndexerPluginImpl")
    _cancellables.map(_.cancel)
  }
  
  override def scrape(): Seq[(NormalizedURI, Option[Article])] = {
    val future = actor.ask(Scrape)(1 minutes).mapTo[Seq[(NormalizedURI, Option[Article])]]
    Await.result(future, 1 minutes)
  } 
}

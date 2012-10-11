package com.keepit.scraper

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
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
    case Scrape => scraper.run()
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ScraperPlugin extends Plugin

class ScraperPluginImpl(system: ActorSystem, scraper: Scraper) extends ScraperPlugin {
  
  implicit val actorTimeout = Timeout(5 seconds)
  
  private val actor = system.actorOf(Props { new ScraperActor(scraper) })
  
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(0 seconds, 1 minutes, actor, Scrape)
    )
  }
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }
}

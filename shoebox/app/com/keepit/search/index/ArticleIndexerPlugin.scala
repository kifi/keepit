package com.keepit.search.index

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

case object Index

private[index] class ArticleIndexerActor(articleIndexer: ArticleIndexer) extends Actor with Logging {
  
  def receive() = {
    case Index => 
      var articlesIndexed = articleIndexer.run()
      if (articlesIndexed >= articleIndexer.commitBatchSize) {
        self ! Index
      }
      sender ! articlesIndexed
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ArticleIndexerPlugin extends Plugin {
  def index(): Int
}

class ArticleIndexerPluginImpl @Inject() (system: ActorSystem, articleIndexer: ArticleIndexer) extends ArticleIndexerPlugin {
  
  implicit val actorTimeout = Timeout(5 seconds)
  
  private val actor = system.actorOf(Props { new ArticleIndexerActor(articleIndexer) })
  
  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    _cancellables = Seq(
      system.scheduler.schedule(30 seconds, 1 minutes, actor, Index)
    )
  }
  override def onStop(): Unit = {
    _cancellables.map(_.cancel)
  }
  
  override def index(): Int = {
    val future = actor.ask(Index)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  } 
}

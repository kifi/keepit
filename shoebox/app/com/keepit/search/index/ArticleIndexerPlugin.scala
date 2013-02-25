package com.keepit.search.index

import scala.collection.mutable.MutableList
import com.keepit.search.ArticleStore
import com.keepit.common.logging.Logging
import play.api.Play.current
import play.api.Plugin
import play.api.templates.Html
import akka.util.Timeout
import akka.actor._
import akka.actor.Actor._
import akka.actor.ActorRef
import play.api.libs.concurrent.Execution.Implicits._
import akka.pattern.ask
import scala.concurrent.Await
import play.api.libs.concurrent._
import org.joda.time.DateTime
import com.google.inject.Inject
import com.google.inject.Provider
import scala.collection.mutable.{Map => MutableMap}
import com.keepit.inject._
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import scala.concurrent.duration._
import scala.concurrent.Future
import com.keepit.common.akka.FortyTwoActor

case object Index

private[index] class ArticleIndexerActor(articleIndexer: ArticleIndexer) extends FortyTwoActor with Logging {

  def receive() = {
    case Index => try {
        var articlesIndexed = articleIndexer.run()
        if (articlesIndexed >= articleIndexer.commitBatchSize) {
          self.forward(Index)
        }
        sender ! articlesIndexed
      } catch {
        case e: Exception =>
          inject[HealthcheckPlugin].addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH, errorMessage = Some("Error indexing articles")))
          sender ! -1
      }
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ArticleIndexerPlugin extends Plugin {
  def index(): Int
}

class ArticleIndexerPluginImpl @Inject() (system: ActorSystem, articleIndexer: ArticleIndexer) extends ArticleIndexerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private val actor = system.actorOf(Props { new ArticleIndexerActor(articleIndexer) })

  // plugin lifecycle methods
  private var _cancellables: Seq[Cancellable] = Nil
  override def enabled: Boolean = true
  override def onStart(): Unit = {
    log.info("starting ArticleIndexerPluginImpl")
    _cancellables = Seq(
      system.scheduler.schedule(30 seconds, 1 minutes, actor, Index)
    )
  }
  override def onStop(): Unit = {
    log.info("stopping ArticleIndexerPluginImpl")
    _cancellables.map(_.cancel)
    articleIndexer.close()
  }

  override def index(): Int = {
    val future = actor.ask(Index)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }
}

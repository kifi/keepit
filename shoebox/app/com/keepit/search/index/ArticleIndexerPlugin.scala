package com.keepit.search.index

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.actor.ActorFactory
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

case object Index

private[index] class ArticleIndexerActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    articleIndexer: ArticleIndexer)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case Index => try {
        val articlesIndexed = articleIndexer.run()
        if (articlesIndexed >= articleIndexer.commitBatchSize) {
          self.forward(Index)
        }
        sender ! articlesIndexed
      } catch {
        case e: Exception =>
          healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH, errorMessage = Some("Error indexing articles")))
          sender ! -1
      }
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait ArticleIndexerPlugin extends SchedulingPlugin {
  def index(): Int
  def reindex()
}

class ArticleIndexerPluginImpl @Inject() (
    actorFactory: ActorFactory[ArticleIndexerActor],
    articleIndexer: ArticleIndexer,
    val schedulingProperties: SchedulingProperties)
  extends ArticleIndexerPlugin with Logging {

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  // plugin lifecycle methods
  override def enabled: Boolean = true
  override def onStart() {
    log.info("starting ArticleIndexerPluginImpl")
    scheduleTask(actorFactory.system, 30 seconds, 1 minutes, actor, Index)
  }
  override def onStop() {
    log.info("stopping ArticleIndexerPluginImpl")
    articleIndexer.close()
  }

  override def index(): Int = {
    val future = actor.ask(Index)(1 minutes).mapTo[Int]
    Await.result(future, 1 minutes)
  }

  override def reindex() {
    articleIndexer.sequenceNumber = SequenceNumber.MinValue
    actor ! Index
  }
}

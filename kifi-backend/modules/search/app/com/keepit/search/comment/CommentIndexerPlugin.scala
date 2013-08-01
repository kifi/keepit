package com.keepit.search.comment

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.Inject
import com.keepit.common.akka.FortyTwoActor
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.{Healthcheck, HealthcheckPlugin, HealthcheckError}
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorFactory
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._

case object Update

private[comment] class CommentIndexerActor @Inject() (
    healthcheckPlugin: HealthcheckPlugin,
    commentIndexer: CommentIndexer)
  extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case Update => try {
        sender ! commentIndexer.update()
      } catch {
        case e: Exception =>
          healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
              errorMessage = Some("Error updating comment index")))
          sender ! -1
      }
    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait CommentIndexerPlugin extends SchedulingPlugin {
  def update(): Future[Int]
  def reindex()
}

class CommentIndexerPluginImpl @Inject() (
    actorFactory: ActorFactory[CommentIndexerActor],
    commentIndexer: CommentIndexer)
  extends CommentIndexerPlugin with Logging {

  val schedulingProperties = SchedulingProperties.AlwaysEnabled
  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.actor

  override def enabled: Boolean = true
  override def onStart() {
    scheduleTask(actorFactory.system, 30 seconds, 1 minute, actor, Update)
    log.info("starting CommentIndexerPluginImpl")
  }
  override def onStop() {
    log.info("stopping CommentIndexerPluginImpl")
    cancelTasks()
    commentIndexer.close()
  }

  override def update(): Future[Int] = actor.ask(Update)(1 minutes).mapTo[Int]

  override def reindex() {
    commentIndexer.reindex()
    actor ! Update
  }
}

package com.keepit.learning.topicmodel

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

case object UpdateTopic
case object ResetTopic

private[topicmodel] class TopicUpdaterActor @Inject() (
  healthcheckPlugin: HealthcheckPlugin,
  topicUpdater: TopicUpdater
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case UpdateTopic => try {
      topicUpdater.update()
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
          errorMessage = Some("Error updating topics")))
    }

    case ResetTopic => try {
      topicUpdater.reset()
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.SEARCH,
          errorMessage = Some("Error resetting topics")))
    }

    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait TopicUpdaterPlugin extends SchedulingPlugin {
  def update(): Unit
  def reset(): Unit
}

class TopicUpdaterPluginImpl @Inject() (
    actorFactory: ActorFactory[TopicUpdaterActor],
    topicUpdater: TopicUpdater,
    val schedulingProperties: SchedulingProperties
) extends TopicUpdaterPlugin with Logging{
  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actorFactory.system, 5 minutes, 2 minutes, actor, UpdateTopic)
     log.info("starting TopicUpdaterPluginImpl")
  }
  override def onStop() {
     log.info("stopping TopicUpdaterPluginImpl")
     cancelTasks()
  }

  override def reset() = {
    log.info("admin reset topic tables ...")
    actor ! ResetTopic
  }

  def update() = actor ! UpdateTopic

}


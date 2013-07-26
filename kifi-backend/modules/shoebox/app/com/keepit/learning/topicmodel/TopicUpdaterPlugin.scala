package com.keepit.learning.topicmodel

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.{Inject, Singleton}
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
import com.keepit.common.zookeeper.CentralConfig

case object UpdateTopic
case object Remodel
case object ContinueRemodel

private[topicmodel] class TopicUpdaterActor @Inject() (
  healthcheckPlugin: HealthcheckPlugin,
  topicUpdater: TopicUpdater,
  topicRemodeler: TopicRemodeler
) extends FortyTwoActor(healthcheckPlugin) with Logging {

  def receive() = {
    case UpdateTopic => try {
      topicUpdater.update()
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some("Error updating topics")))
    }

    case Remodel => try {
      topicRemodeler.remodel(continueFromLastInteruption = false)
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some("Error reconstructing topic model")))
    }

    case ContinueRemodel => try {
      topicRemodeler.remodel(continueFromLastInteruption = true)
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some("Error reconstructing topic model")))
    }

    case m => throw new Exception("unknown message %s".format(m))
  }
}

trait TopicUpdaterPlugin extends SchedulingPlugin {
  def remodel(): Unit
}

@Singleton
class TopicUpdaterPluginImpl @Inject() (
    actorFactory: ActorFactory[TopicUpdaterActor],
    centralConfig: CentralConfig,
    val schedulingProperties: SchedulingProperties //only on leader
) extends TopicUpdaterPlugin with Logging{

  implicit val actorTimeout = Timeout(5 seconds)

  private lazy val actor = actorFactory.get()

  override def enabled: Boolean = true
  override def onStart() {
     scheduleTask(actorFactory.system, 10 minutes, 2 minutes, actor, UpdateTopic)
     log.info("starting TopicUpdaterPluginImpl")
     checkRemodelStatusOnStart()
  }
  override def onStop() {
     log.info("stopping TopicUpdaterPluginImpl")
     cancelTasks()
  }

  private def checkRemodelStatusOnStart() = {
    val remodelKey = new TopicRemodelKey()
    if (centralConfig(remodelKey) == RemodelState.STARTED){
      scheduleTaskOnce(actorFactory.system, 10 minutes, "will continue reconstructing topic model in 10 minutes ...")(actor ! ContinueRemodel)
    } else if (centralConfig(remodelKey) != RemodelState.DONE){
      log.info("defaulting remodel state to DONE")
      centralConfig(remodelKey) = RemodelState.DONE
    }
  }

  // triggered from admin
  override def remodel() = {
    log.info("admin reconstruct topic model ...")
    scheduleTaskOnce(actorFactory.system, 1 seconds, "reconstruct topic model")(actor ! Remodel)
  }

}


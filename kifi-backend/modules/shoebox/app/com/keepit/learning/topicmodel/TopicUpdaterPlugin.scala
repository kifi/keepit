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
case object SwitchModel

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

    case SwitchModel => try {
      if (topicUpdater.checkFlagConsistency){
        log.info("SwitchModel msg received but ignored. Internal flag already matches central config")
      } else {
        log.info("SwitchModel msg received. Will refresh and switch model.")
        topicUpdater.refreshAndSwitchModel()
      }
    } catch {
      case e: Exception =>
        healthcheckPlugin.addError(HealthcheckError(error = Some(e), callType = Healthcheck.INTERNAL,
          errorMessage = Some("Error handling SwitchModel message")))
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
     log.info("starting TopicUpdaterPluginImpl")
     scheduleTask(actorFactory.system, 10 minutes, 2 minutes, actor, UpdateTopic)
     watchModelFlag()
     scheduleTask(actorFactory.system, 1 minutes, 3650 days, "check remodel status")(watchRemodelStatus)
  }
  override def onStop() {
     log.info("stopping TopicUpdaterPluginImpl")
     cancelTasks()
  }

  def watchModelFlag() = {
    log.info("watching model flag")
    val flagKey = new TopicModelFlagKey()
    centralConfig.onChange(flagKey){ flagOpt =>
      log.info("topic model flag may have changed. Send a msg to TopicUpdater actor. ")
      actor ! SwitchModel
    }
  }

  /**
   * This will be effectively scheduled once for each machine. Only the leader
   * can initialize the remodel process. If the leader is dead, the new leader
   * will continue the remodel process.
   */
  private def watchRemodelStatus() = {
    log.info("watching remodel status")
    val remodelKey = new TopicRemodelKey()
    val remodelStat = centralConfig(remodelKey)

    if (!remodelStat.isDefined) {
      log.info("remodel Key is not defined yet. Defaulting to DONE")
      centralConfig(remodelKey) = RemodelState.DONE
    } else {
      log.info(s"current remodel status is ${remodelStat.get}")
    }

    if (remodelStat == RemodelState.STARTED){
      actor ! ContinueRemodel
    }

    centralConfig.onChange(remodelKey){ flagOpt =>
      if (flagOpt.isDefined && (flagOpt.get == RemodelState.NEEDED)){
        actor ! Remodel
      }
    }
  }

  // triggered from admin
  override def remodel() = {
    log.info("admin reconstruct topic model ...")
    centralConfig.update(new TopicRemodelKey(), RemodelState.NEEDED)
  }

}


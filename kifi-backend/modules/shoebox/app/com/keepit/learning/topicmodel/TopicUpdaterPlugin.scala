package com.keepit.learning.topicmodel

import akka.actor._
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.{Inject, Singleton}
import com.keepit.common.akka.{FortyTwoActor, UnsupportedActorMessage}
import com.keepit.common.db.SequenceNumber
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{SchedulingPlugin, SchedulingProperties}
import com.keepit.common.actor.ActorInstance
import com.keepit.inject._
import play.api.Play.current
import scala.concurrent.Future
import scala.concurrent.duration._
import com.keepit.common.zookeeper.CentralConfig
import play.api.Plugin

case object UpdateTopic
case object Remodel
case object ContinueRemodel
case object SwitchModel

private[topicmodel] class TopicUpdaterActor @Inject() (
  airbrake: AirbrakeNotifier,
  topicUpdater: TopicUpdater,
  topicRemodeler: TopicRemodeler
) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case UpdateTopic => topicUpdater.update()

    case SwitchModel => {
      if (topicUpdater.checkFlagConsistency){
        log.info("SwitchModel msg received but ignored. Internal flag already matches central config")
      } else {
        log.info("SwitchModel msg received. Will refresh and switch model.")
        topicUpdater.refreshAndSwitchModel()
      }
    }

    case Remodel => topicRemodeler.remodel(continueFromLastInteruption = false)
    case ContinueRemodel => topicRemodeler.remodel(continueFromLastInteruption = true)
    case m => throw new UnsupportedActorMessage(m)
  }
}

trait TopicUpdaterPlugin extends SchedulingPlugin {
  def remodel(): Unit
}

@Singleton
class TopicUpdaterPluginImpl @Inject() (
    actor: ActorInstance[TopicUpdaterActor],
    centralConfig: CentralConfig,
    val schedulingProperties: SchedulingProperties //only on leader
) extends TopicUpdaterPlugin with Logging{

  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true
  override def onStart() {
     log.info("starting TopicUpdaterPluginImpl")
     scheduleTask(actor.system, 10 minutes, 2 minutes, actor.ref, UpdateTopic)
     scheduleTask(actor.system, 30 seconds, 3650 days, "check remodel status")(watchRemodelStatus)
  }
  override def onStop() {
     log.info("stopping TopicUpdaterPluginImpl")
     cancelTasks()
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
      actor.ref ! ContinueRemodel
    }

    centralConfig.onChange(remodelKey){ flagOpt =>
      if (flagOpt.isDefined && (flagOpt.get == RemodelState.NEEDED)){
        actor.ref ! Remodel
      }
    }
  }

  // triggered from admin
  override def remodel() = {
    log.info("admin reconstruct topic model ...")
    centralConfig.update(new TopicRemodelKey(), RemodelState.NEEDED)
  }

}

trait TopicModelSwitcherPlugin extends Plugin

@Singleton
class TopicModelSwitcherPluginImpl @Inject() (
  actor: ActorInstance[TopicUpdaterActor],
  centralConfig: CentralConfig
) extends TopicModelSwitcherPlugin with Logging {
  implicit val actorTimeout = Timeout(5 seconds)

  override def enabled: Boolean = true

  override def onStart() {
     log.info("starting TopicModelSwitcherPluginImpl")
     watchModelFlag()
  }
  override def onStop() {
     log.info("stopping TopicModelSwitcherPluginImpl")
  }

  def watchModelFlag() = {
    log.info("watching model flag")
    val flagKey = new TopicModelFlagKey()
    centralConfig.onChange(flagKey){ flagOpt =>
      log.info("topic model flag may have changed. Send a msg to TopicUpdater actor. ")
      actor.ref ! SwitchModel
    }
  }
}

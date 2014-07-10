package com.keepit.heimdal

import com.keepit.commander.DelightedCommander

import scala.concurrent.duration._

import com.google.inject.{ ImplementedBy, Inject }
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import play.api.Plugin

private case object FetchNewDelightedAnswers

class DelightedAnswerReceiverActor @Inject() (
    airbrake: AirbrakeNotifier,
    commander: DelightedCommander) extends FortyTwoActor(airbrake) with Logging {

  def receive = {
    case FetchNewDelightedAnswers =>
      log.info("Checking for new answers")
      commander.fetchNewDelightedAnswers()
    case m => throw new UnsupportedActorMessage(m)
  }
}

@ImplementedBy(classOf[DelightedPluginImpl])
trait DelightedPlugin extends Plugin {
  def fetchNewDelightedAnswers()
}

class DelightedPluginImpl @Inject() (
    actor: ActorInstance[DelightedAnswerReceiverActor],
    val scheduling: SchedulingProperties //only on leader
    ) extends DelightedPlugin with SchedulerPlugin {

  override def enabled: Boolean = true

  def fetchNewDelightedAnswers() {
    actor.ref ! FetchNewDelightedAnswers
  }
  override def onStart() {
    scheduleTaskOnLeader(actor.system, 10 seconds, 15 seconds, actor.ref, FetchNewDelightedAnswers)
  }
}

class DevDelightedPlugin @Inject() extends DelightedPlugin with Logging {
  def fetchNewDelightedAnswers() {
    log.info("Fake fetching new delighted answers")
  }
}

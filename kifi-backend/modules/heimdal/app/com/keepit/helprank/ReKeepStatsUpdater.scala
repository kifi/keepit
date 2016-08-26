package com.keepit.helprank

import com.keepit.commander.AttributionCommander
import play.api.Plugin
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }

trait ReKeepStatsUpdaterPlugin extends Plugin {
  def updateReKeepStats()
}

case class UpdateStats()

class ReKeepStatsUpdaterPluginImpl @Inject() (
    actor: ActorInstance[ReKeepStatsUpdater],
    val scheduling: SchedulingProperties) extends Logging with ReKeepStatsUpdaterPlugin with SchedulerPlugin {

  log.info(s"[ReKeepStatsUpdaterPlugin.ctr] created! actor=${actor.ref.path}")

  override def enabled: Boolean = true
  override def onStart() { //kill
    //    scheduleTaskOnOneMachine(actor.system, 15 minutes, 30 minutes, actor.ref, UpdateStats, UpdateStats.getClass.getSimpleName) // tweak
  }

  override def updateReKeepStats(): Unit = { actor.ref ! UpdateStats }
}

class ReKeepStatsUpdater @Inject() (
    airbrake: AirbrakeNotifier,
    attributionCmdr: AttributionCommander) extends FortyTwoActor(airbrake) with Logging {
  def receive() = {
    case UpdateStats => attributionCmdr.updateAllReKeepStats(1)
    case m => throw new UnsupportedActorMessage(m)
  }
}

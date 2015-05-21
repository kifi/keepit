package com.keepit.scraper

import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.akka.{ FortyTwoActor, UnsupportedActorMessage }
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.logging.Logging
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import play.api.Mode.Mode
import play.api.{ Mode, Plugin }

import scala.concurrent.duration._

trait PullerPlugin extends Plugin {
  def pull()
}

case class Pull()

class PullerPluginImpl @Inject() (
    actor: ActorInstance[Puller],
    mode: Mode,
    scraperConfig: ScraperConfig,
    val scheduling: SchedulingProperties) extends Logging with PullerPlugin with SchedulerPlugin {

  override def enabled: Boolean = false
/*  override def onStart() {
    val (initDelay, freq) = if (mode == Mode.Dev) (5 seconds, 5 seconds) else (scraperConfig.pullFrequency seconds, scraperConfig.pullFrequency seconds)
    log.info(s"[onStart] PullerPlugin started with initDelay=$initDelay freq=$freq")
    scheduleTaskOnAllMachines(actor.system, initDelay, freq, actor.ref, Pull)
  }*/

  override def pull() { actor.ref ! Pull }
}

class Puller @Inject() (
    airbrake: AirbrakeNotifier,
    scrapeProcessor: ScrapeProcessor) extends FortyTwoActor(airbrake) with Logging {

  def receive() = {
    case Pull => scrapeProcessor.pull
    case m => throw new UnsupportedActorMessage(m)
  }

}
package com.keepit.scraper

import play.api.{ Play, Plugin }
import play.api.Play.current
import com.google.inject.Inject
import com.keepit.common.actor.ActorInstance
import com.keepit.common.plugin.{ SchedulerPlugin, SchedulingProperties }
import com.keepit.common.logging.Logging
import scala.concurrent.duration._
import com.keepit.common.healthcheck.AirbrakeNotifier
import com.keepit.common.akka.{ UnsupportedActorMessage, FortyTwoActor }

trait PullerPlugin extends Plugin {
  def pull()
}

case class Pull()

class PullerPluginImpl @Inject() (
    actor: ActorInstance[Puller],
    scraperConfig: ScraperConfig,
    val scheduling: SchedulingProperties) extends Logging with PullerPlugin with SchedulerPlugin {

  override def enabled: Boolean = true
  override def onStart() {
    if (Play.maybeApplication.isDefined) {
      val (initDelay, freq) = if (Play.isDev) (5 seconds, 5 seconds) else (scraperConfig.pullFrequency seconds, scraperConfig.pullFrequency seconds)
      log.info(s"[onStart] PullerPlugin started with initDelay=$initDelay freq=$freq")
      scheduleTaskOnAllMachines(actor.system, initDelay, freq, actor.ref, Pull)
    } else {
      log.error(s"[onStart] PullerPlugin NOT started -- play app is not ready")
    }
  }

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